// The troubleshooting guides under /troubleshooting/: English only, in literal English, like the
// legal and support pages (no catalog strings, so a new guide never lowers a language's share).
// Every claim is a menu or setting the app has: the player's menu (Try the other decoder, Open in
// another app), Settings (Playback, Prefer software video, Low storage mode, Proxy), Downloads
// (Free up space). "Video", never "movie".

export interface Step { h: string; p: string }
export interface Guide {
  slug: string;
  crumb: string;
  title: string;
  description: string;
  h1: [string, string];
  lede: string;
  icon: string;
  card: string;
  steps: Step[];
  whyH: string;
  why: string[];
  stillH: string;
  still: string;
  updated: string;
}

export const GUIDES: Guide[] = [
  {
    slug: 'video-will-not-start',
    crumb: 'A video will not start',
    title: 'A Telegram video will not start or keeps buffering',
    description: 'What to try when a video in TMPlayer will not start, freezes or keeps buffering: the connection, free space, the other decoder, and opening it in VLC or MX Player.',
    h1: ['A video will not start', 'or keeps buffering'],
    lede: 'Most of the time it is the connection, the space on the device, or a chip that draws the picture badly. These steps go from the quickest check to handing the video to another player.',
    icon: 'play-card',
    card: 'The connection, free space, the other decoder and Open in another app, in the order to try them.',
    steps: [
      { h: 'Look at the speed', p: 'A video starts while it is still arriving, so a slow or cut connection shows as buffering. While it waits TMPlayer shows the speed: at 0 the network is the cause, not the player. Try Wi-Fi instead of mobile data, or choose Download in the video\'s menu and play it from Downloads once it is all there.' },
      { h: 'Check there is room', p: 'A full device cannot keep the part of the video it is playing. TMPlayer says "Not enough space" with how much is short. The guide for a full phone has the fixes, and Low storage mode streams with only a small part kept.' },
      { h: 'Try the other decoder', p: 'On some chips the hardware decoder draws green, blocky or frozen frames and reports no error. In the player\'s menu choose Try the other decoder, or turn on Prefer software video in Settings, Playback, and the app\'s own decoder draws the picture.' },
      { h: 'Open it in another app', p: 'In the player\'s menu choose Open in another app (on a TV, hold OK on the video), and pick VLC, MX Player or any other player on the device. A video still downloading goes over as far as it has got. Videos from chats that do not allow saving stay in TMPlayer.' },
      { h: 'Send the logs', p: 'If it keeps happening, Send logs shows what would be sent before it goes, and tells the developer which device, codec and connection it was. That is what lets it be fixed for the next person.' },
    ],
    whyH: 'Why a video may not play',
    why: [
      'A video posted in pieces (Name.part001, Name.mkv.001) plays only when every piece is in the chat. TMPlayer says which piece is missing.',
      'A video kept in a ZIP plays only when the ZIP stores it uncompressed. A compressed ZIP, RAR or 7z has to be unpacked with a file manager first, and the chat\'s list says so.',
      'Seeking far ahead restarts the download from that point, so a jump into a part that has not arrived waits for it.',
      'A file that is not a video, or is cut short, has nothing to play. Opening it in Telegram shows what it is.',
    ],
    stillH: 'Still stuck?',
    still: 'Ask in the Telegram group or open an issue on GitHub, with the logs from Send logs if you can. Say the device, the video\'s size and format, and whether it plays from Downloads.',
    updated: '2026-11-15',
  },
  {
    slug: 'telegram-blocked-on-this-network',
    crumb: 'Telegram blocked',
    title: 'Telegram blocked on this network: sign in with a proxy',
    description: 'TMPlayer cannot connect or sign in because Telegram is blocked on your network. Try another network, or add a proxy link or a server by hand, and how to stop using it.',
    h1: ['Telegram is blocked', 'on this network'],
    lede: 'TMPlayer talks to Telegram directly, so a school, office, country or Wi-Fi that blocks Telegram blocks it too. A proxy can carry the connection.',
    icon: 'tv-card',
    card: 'Another network, or a proxy by link or by hand, and how to switch it off again.',
    steps: [
      { h: 'Recognise it', p: 'The sign in screen says "Telegram may be blocked on this network. A proxy can get you through." when the connection never completes. If Telegram\'s own app is blocked in the same place, the network is the cause.' },
      { h: 'Try another network', p: 'Switch the phone to mobile data, or the TV to a hotspot, for the sign in. Once signed in, TMPlayer keeps working on the same account.' },
      { h: 'Add a proxy', p: 'Choose Use a proxy on the sign in screen, or open Settings, Proxy. Paste a proxy link (it starts with t.me/proxy, t.me/socks, tg://proxy or tg://socks), or enter the server and port yourself. MTProto needs its secret too; SOCKS5 and HTTP may take a user name and password.' },
      { h: 'Test it', p: 'Test shows how fast the proxy answers. "No answer from this proxy" means try another one. The proxy in use is marked In use.' },
      { h: 'Turn it off when you are home', p: 'Choose Stop using proxy in Settings, Proxy, and TMPlayer connects directly again.' },
    ],
    whyH: 'Worth knowing',
    why: [
      'Only use a proxy you trust. It can see that you use Telegram, but not your messages or videos.',
      'TMPlayer has no server of its own and runs no proxy. A proxy link comes from somewhere else, such as a channel that shares them.',
      'A proxy only helps the connection to Telegram. A video still plays from Telegram\'s own servers, so a slow proxy means a slow start.',
    ],
    stillH: 'Still not connecting?',
    still: 'Say which network it is (a country, a school, a hotel) and what the sign in screen says, in the Telegram group or on GitHub. Do not post a proxy secret or your login code.',
    updated: '2026-11-15',
  },
  {
    slug: 'phone-storage-full',
    crumb: 'Phone storage full',
    title: 'Phone storage full: keep playing Telegram videos',
    description: 'A full phone or TV stick cannot keep a video it is playing. Turn on Low storage mode, free up space in TMPlayer without touching Telegram, and stop it filling up again.',
    h1: ['Storage is full,', 'keep watching'],
    lede: 'TMPlayer keeps the video you play so going back is instant, and downloads add to it. On a device that is always full, three settings keep it playing.',
    icon: 'play-card',
    card: 'Low storage mode, Free up space and the warning before a device runs out.',
    steps: [
      { h: 'Turn on Low storage mode', p: 'In Settings, switch on Low storage mode. Videos then stream with only a small part kept on the device instead of the whole file, and your downloads stay as they are. Seeking back downloads that part again, with a short pause now and then. When a video does not fit, TMPlayer asks whether to switch and play.' },
      { h: 'Free up space', p: 'Choose Free up space in Downloads to see what TMPlayer is holding and remove what you have watched. Nothing is removed from Telegram: you can play or download any of it again.' },
      { h: 'Download what fits', p: 'A whole season may not fit. Pick the episodes you need now, and TMPlayer says when a video needs more room than is free.' },
      { h: 'Get a warning, not a stall', p: 'Leave Warn when space runs low on in Settings. The notification says how much is free, so you clear space before a video stalls.' },
    ],
    whyH: 'Where the space goes',
    why: [
      'Videos you played stay whole in Recently played while Low storage mode is off, so going back is instant. That is the one to clear first.',
      'Downloads you asked for stay until you remove them.',
      'On a TV stick with 8 GB, a single large video can be most of the free space: Low storage mode is made for that.',
    ],
    stillH: 'Space still short?',
    still: 'A phone\'s own storage settings show what else is using it. TMPlayer never needs more than the video it is playing in Low storage mode.',
    updated: '2026-11-15',
  },
];
