// Where the site's support links go. One place, so the floating card, the support offer, the
// footers and the support page cannot drift apart. nevil.dev is the Buy Me a Coffee page the app's
// About screen links too (buymeacoffee.com/nevil is somebody else's).
export const BMC_URL = 'https://buymeacoffee.com/nevil.dev';
export const SPONSORS_URL = 'https://github.com/sponsors/dracu-lah';

// Buy Me a Coffee takes cards and PayPal, neither of which most of TMPlayer's readers use: they
// are in India and pay by UPI (user, 2026-11-01). The pay link is what a UPI app reads, and what
// /brand/upi-qr.svg encodes, so GPay, PhonePe, Paytm, BHIM or a bank app all take it. On a phone
// the link opens the app; a computer or a television gets the QR and the ID to copy, since there
// is nothing there to open and a phone cannot scan its own screen.
export const UPI_ID = 'nevilnicks4321@okaxis';
export const UPI_LINK = `upi://pay?pa=${UPI_ID}&pn=TMPlayer&cu=INR&tn=${encodeURIComponent('TMPlayer support')}`;
export const UPI_QR = '/brand/upi-qr.svg';
