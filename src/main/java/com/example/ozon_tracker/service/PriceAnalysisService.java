package com.example.ozon_tracker.service;

import com.example.ozon_tracker.dto.OzonCartItemDto;
import com.example.ozon_tracker.dto.PriceComparison;
import com.example.ozon_tracker.dto.ScanResult;
import com.example.ozon_tracker.model.CartItem;
import com.example.ozon_tracker.model.PriceHistory;
import com.example.ozon_tracker.model.enums.Availability;
import com.example.ozon_tracker.model.enums.ItemStatus;
import com.example.ozon_tracker.repository.CartItemRepository;
import com.example.ozon_tracker.repository.PriceHistoryRepository;
import com.example.ozon_tracker.scraping.CartSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ядро бизнес-логики: сохраняет снимок корзины в БД и сравнивает актуальные цены
 * с историческим минимумом (строгое равенство: AT_MINIMUM <=> currentPrice == historicalMin).
 */
@Service
public class PriceAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(PriceAnalysisService.class);

    private final CartItemRepository cartItemRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    public PriceAnalysisService(CartItemRepository cartItemRepository, PriceHistoryRepository priceHistoryRepository) {
        this.cartItemRepository = cartItemRepository;
        this.priceHistoryRepository = priceHistoryRepository;
    }

    @Transactional
    public ScanResult processSnapshot(CartSnapshot snapshot) {
        LocalDateTime now = LocalDateTime.now();
        List<OzonCartItemDto> items = snapshot.items();
        List<PriceComparison> comparisons = new ArrayList<>();
        List<ScanResult.OutOfStockItem> outOfStock = new ArrayList<>();
        BigDecimal cartTotal = BigDecimal.ZERO;
        int newItems = 0;

        Set<String> seenSkus = new HashSet<>();

        for (OzonCartItemDto dto : items) {
            seenSkus.add(dto.sku());
            CartItem entity = cartItemRepository.findByOzonItemId(dto.sku()).orElse(null);
            boolean firstObservation = entity == null;

            // «Предыдущая» цена — та, что была в БД до этого замера
            BigDecimal previous = entity != null ? entity.getCurrentPrice() : null;

            if (firstObservation) {
                BigDecimal basePrice = dto.price() != null ? dto.price() : BigDecimal.ZERO;
                entity = CartItem.builder()
                        .ozonSku(dto.sku())
                        .name(dto.name())
                        .url(dto.url())
                        .currentPrice(dto.price())
                        .historicalMinPrice(basePrice)
                        .quantity(dto.quantity())
                        .availability(Availability.fromInStock(dto.inStock()))
                        .inCart(true)
                        .lastSeenAt(now)
                        .build();
                entity = cartItemRepository.save(entity);
                newItems++;
            } else {
                // Возврат удалённой позиции в корзину
                if (!Boolean.TRUE.equals(entity.getInCart())) {
                    entity.setInCart(true);
                    entity.setRemovedFromCartAt(null);
                }
                entity.setName(dto.name());
                entity.setUrl(dto.url());
                entity.setQuantity(dto.quantity());
                entity.setAvailability(Availability.fromInStock(dto.inStock()));
                entity.setLastSeenAt(now);
                if (dto.price() != null) {
                    entity.setCurrentPrice(dto.price());
                    if (entity.getHistoricalMinPrice() == null
                            || dto.price().compareTo(entity.getHistoricalMinPrice()) < 0) {
                        entity.setHistoricalMinPrice(dto.price());
                    }
                }
                entity = cartItemRepository.save(entity);
            }

            if (dto.price() != null) {
                priceHistoryRepository.save(PriceHistory.builder()
                        .cartItem(entity)
                        .price(dto.price())
                        .inStock(dto.inStock())
                        .recordedAt(now)
                        .build());
                if (dto.inStock()) {
                    cartTotal = cartTotal.add(dto.price().multiply(BigDecimal.valueOf(dto.quantity())));
                }
            }

            if (!dto.inStock()) {
                outOfStock.add(new ScanResult.OutOfStockItem(
                        dto.sku(), dto.name(), dto.url(),
                        entity.getCurrentPrice() != null ? entity.getCurrentPrice() : entity.getHistoricalMinPrice()));
            }

            comparisons.add(buildComparison(entity, dto, previous, firstObservation));
        }

        // Позиции, которые были в корзине в БД, но исчезли со страницы
        List<ScanResult.RemovedItem> removed = new ArrayList<>();
        for (CartItem tracked : cartItemRepository.findAllActiveCartItems()) {
            if (!seenSkus.contains(tracked.getOzonSku())) {
                tracked.setInCart(false);
                tracked.setAvailability(Availability.NOT_IN_CART);
                tracked.setRemovedFromCartAt(now);
                cartItemRepository.save(tracked);
                removed.add(new ScanResult.RemovedItem(
                        tracked.getOzonSku(), tracked.getName(), tracked.getCurrentPrice(), now));
                log.info("Позиция исчезла из корзины: {} ({})", tracked.getName(), tracked.getOzonSku());
            }
        }

        return new ScanResult(now, LocalDateTime.now(), items.size(), newItems,
                comparisons, removed, outOfStock, cartTotal, null);
    }

    /**
     * Статус: NEW при первом замере; иначе сравнение с предыдущей ценой и
     * СТРОГОЕ равенство с историческим минимумом для AT_MINIMUM.
     */
    private PriceComparison buildComparison(CartItem entity, OzonCartItemDto dto,
                                            BigDecimal previous, boolean firstObservation) {
        BigDecimal current = dto.price();
        BigDecimal min = entity.getHistoricalMinPrice();

        ItemStatus status;
        if (firstObservation) {
            status = ItemStatus.NEW;
        } else if (current == null) {
            status = ItemStatus.UNCHANGED;
        } else if (min != null && current.compareTo(min) == 0) {
            // строгое равенство минимальной цене
            status = ItemStatus.AT_MINIMUM;
        } else if (previous == null) {
            status = ItemStatus.NEW;
        } else {
            int cmp = current.compareTo(previous);
            status = cmp < 0 ? ItemStatus.DECREASED : cmp > 0 ? ItemStatus.INCREASED : ItemStatus.UNCHANGED;
        }

        BigDecimal diffToMinAbs = (current != null && min != null)
                ? current.subtract(min).setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal diffToMinPercent = null;
        if (diffToMinAbs != null && min != null && min.signum() > 0) {
            diffToMinPercent = diffToMinAbs.multiply(BigDecimal.valueOf(100))
                    .divide(min, 1, RoundingMode.HALF_UP);
        }
        BigDecimal diffToPrevAbs = (current != null && previous != null)
                ? current.subtract(previous).setScale(2, RoundingMode.HALF_UP) : null;

        return new PriceComparison(dto.sku(), dto.name(), dto.url(), current, min, previous,
                diffToMinAbs, diffToMinPercent, diffToPrevAbs, dto.quantity(),
                status, dto.inStock(), firstObservation);
    }
}
