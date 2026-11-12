package dev.pulse.client.module;

public enum Category {
    HUD("HUD"), VISUAL("Visual"), GAMEPLAY("Gameplay"), UTILITY("Utility");

    public final String label;

    Category(String label) {
        this.label = label;
    }
}
