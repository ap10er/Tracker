package com.example.ozon_tracker.model.enums;

/**
 * Статус позиции относительно исторического минимума.
 */
public enum ItemStatus {

    /** Цена ниже (или равна) исторического минимума — лучшее время для покупки. */
    AT_MINIMUM("🟢 МИНИМУМ"),

    /** Цена упала по сравнению с прошлым замером, но выше минимума. */
    DECREASED("🔵 дешевле"),

    /** Цена выросла по сравнению с прошлым замером. */
    INCREASED("🔴 дороже"),

    /** Цена не изменилась. */
    UNCHANGED("⚪ без изменений"),

    /** Первый замер: сравнивать ещё не с чем. */
    NEW("🆕 новый");

    private final String label;

    ItemStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
