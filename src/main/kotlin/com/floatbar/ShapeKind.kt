package com.floatbar

enum class ShapeKind(val displayName: String) {
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
    LINE("Line"),
    ARROW("Arrow"),
    PROCESS("Process"),
    DECISION("Decision / If"),
    START_END("Start / End"),
    INPUT_OUTPUT("Input / Output"),
    DOCUMENT("Document"),
    CONNECTOR("Connector")
}
