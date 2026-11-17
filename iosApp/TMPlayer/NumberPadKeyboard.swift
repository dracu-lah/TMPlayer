import UIKit

/// On iPad, iPadOS shows a number or phone keypad as a small popover anchored to the text field.
/// Compose's text input does not tell iPadOS where its field is, so the popover lands in the top
/// left corner, away from the field (the sign in screen's phone number on the iPad, 2026-11-11).
/// Compose's fields answer that they take no popover, and the keypad docks at the bottom with the
/// rest of the keyboard, as it does on Android tablets. UIKit's own text fields and views have their
/// own answer, so they are left as iOS makes them.
extension UIView {
    @objc var allowsNumberPadPopover: Bool { false }
}
