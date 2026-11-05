package com.example.ozon_tracker.repository;

import com.example.ozon_tracker.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByOzonItemId(String ozonItemId);

    List<CartItem> findByIsInCartTrue();

    @Query("SELECT c FROM CartItem c WHERE c.isInCart = true")
    List<CartItem> findAllActiveCartItems();
}