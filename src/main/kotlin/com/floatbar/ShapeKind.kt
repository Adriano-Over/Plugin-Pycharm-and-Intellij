package com.floatbar

enum class ShapeKind(val displayName: String) {
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
    LINE("Line"),
    ARROW("Arrow"),
    PROCESS("Process"),
    PREDEFINED_PROCESS("Predefined Process"),
    DECISION("Decision / If"),
    START_END("Terminator"),
    INPUT_OUTPUT("Input / Output"),
    DOCUMENT("Document"),
    MULTIPLE_DOCUMENTS("Multiple Documents"),
    CONNECTOR("Connector"),
    STORED_DATA("Stored Data"),
    MANUAL_OPERATION("Manual Operation"),
    BALLOON("Balloon"),
    TEXT("Text"),
    RIGHT_BRACE("Curly bracket");

    fun isClosedOutline(): Boolean {
        return this != LINE && this != ARROW && this != TEXT && this != RIGHT_BRACE
    }

    fun usesRigidObjectAnchoring(): Boolean {
        return true
    }
}
