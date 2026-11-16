// The troubleshooting guides under /troubleshooting/: English only, in literal English, like the
// legal and support pages (no catalog strings, so a new guide never lowers a language's share).
// Every claim is a menu or setting the app has: the player's menu (Try the other decoder, Report
// stutter, Open in another app), Settings (While streaming: Keep video or Save space, Decoder,
// Smallest video shown, Playback test, Proxy), Downloads (Free up space). Each guide: what you
// see (the lede), at most three steps, what TMPlayer does by itself (the "why" list) and when to
// send logs (the "still" text). "Video", never "movie".

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
    description: 'What to try when a video in TMPlayer will not start or keeps buffering: the connection, Save space or Keep video, free space, and opening it in another player.',
    h1: ['A video will not start', 'or keeps buffering'],
    lede: 'You press Play and it stays on the Starting screen, or the wheel keeps turning. Most of the time it is the connection or the space on the device.',
    icon: 'play-card',
    card: 'The connection, Save space or Keep video, free space and another player, in the order to try them.',
    steps: [
      { h: 'Look at the speed and the space', p: 'A video starts while it is still arriving, so a slow or cut connection shows as waiting. TMPlayer shows the speed: at 0 the network is the cause, not the player, so try Wi-Fi instead of mobile data. If it says "Not enough space", it also says how much is short: choose Free up space in Downloads.' },
      { h: 'Switch Keep video to Save space', p: 'In Settings, under While streaming, Keep video needs room for the whole video, and Save space keeps only the part you are watching. On a full phone or a small TV stick, Save space is the one that starts. When a video does not fit, TMPlayer offers to switch and play it.' },
      { h: 'Download it first, or play it in another app', p: 'Choose Download in the video\'s menu and play it from Downloads once it is all there. Or choose Open in another app (on a TV, hold OK on the video) and pick VLC, MX Player or any player on the device. Videos from chats that do not allow saving stay in TMPlayer.' },
    ],
    whyH: 'What TMPlayer does by itself',
    why: [
      'After about ten seconds without a picture it says why (the connection, the space or the video itself) and offers Try again, Download first or Back.',
      'If the picture never starts but the sound does, it tries another decoder or player for that video, up to twice, and remembers what worked on this device. The guide on sound without a picture has the details.',
      'A video posted in pieces (Name.part001, Name.mkv.001) plays only when every piece is in the chat. TMPlayer says which piece is missing.',
      'A video kept in a compressed ZIP, RAR or 7z cannot be played from the archive: unpack it with a file manager first. An uncompressed ZIP plays as it is.',
    ],
    stillH: 'When to send logs',
    still: 'If a video that should play keeps failing, open Settings, then Send logs, and read what would be sent before you press Send. Names, numbers and file names are taken out first, and nothing goes by itself. Then say the device, the video\'s size and format, and whether it plays from Downloads, in the Telegram group or on GitHub.',
    updated: '2026-11-16',
  },
  {
    slug: 'video-stutters',
    crumb: 'Video stutters',
    title: 'Telegram video stutters, freezes or skips frames',
    description: 'A video in TMPlayer that stutters, freezes or drops frames: what to try in three steps, what TMPlayer fixes by itself, and how Report stutter helps the developer.',
    h1: ['A video stutters', 'or freezes'],
    lede: 'The sound goes on but the picture jumps, freezes for a moment or lags behind. It comes from the line, the device or the file, and each has its own fix.',
    icon: 'play-card',
    card: 'Smooth it with another decoder or a lighter copy, and tell the developer with Report stutter.',
    steps: [
      { h: 'Press Report stutter, then try the other decoder', p: 'In the player\'s menu, press Report stutter: it marks that moment so the logs show the last minute (on a TV, hold Info on the remote). Then choose Try the other decoder. Some chips draw a video badly with their own decoder and the app\'s own one is smoother, or the other way round.' },
      { h: 'Pick a lighter copy', p: 'A big 4K or high frame rate video can be more than a small phone or a TV stick can draw. When TMPlayer knows a smaller copy of the same video in your chats, it offers it ("Stuttering? The 1080p copy plays smoothly"), and Copies on the video\'s page lists the others.' },
      { h: 'Run the Playback test', p: 'In Settings, open Playback test. It plays 30 seconds of a video from the device itself, muted, and says whether the device or the line is the problem. If the device plays it smoothly, the stutter was the connection: use Wi-Fi, or download the video first.' },
    ],
    whyH: 'What TMPlayer does by itself',
    why: [
      'It warns before you press Play when a copy uses a format this device can only decode slowly ("May stutter on this phone"), and suggests a smoother one.',
      'When the picture freezes while the sound goes on, or a seek leaves a frozen frame, it starts that video again with another decoder, and if that fails, another player. It changes at most twice per video, says so when it does, and remembers what worked on this kind of device so the next video starts that way.',
      'Settings, Switch engine on decoder error, keeps this on. You do not need to find it unless you want it off.',
    ],
    stillH: 'When to send logs',
    still: 'If it stutters on the same video again after those steps, press Report stutter right at the moment it happens, then open Settings, Send logs. The report holds the decoder, the format and the dropped frames of that last minute, with names and file names taken out, and nothing is sent until you press Send. Say the device model in the Telegram group or on GitHub.',
    updated: '2026-11-16',
  },
  {
    slug: 'sound-without-picture',
    crumb: 'Sound but no picture',
    title: 'Sound but no picture, or picture but no sound',
    description: 'A video in TMPlayer that plays sound over a black or frozen picture, or a picture with no sound: three steps, and what the app tries by itself with another decoder or player.',
    h1: ['Sound but no picture,', 'or picture but no sound'],
    lede: 'You hear the video but the screen is black, green or frozen. Or you see it and hear nothing. The file is usually fine; the decoder on this device did not cope with one part of it.',
    icon: 'play-card',
    card: 'Black or frozen picture with sound, or silence over a good picture: the quickest fixes.',
    steps: [
      { h: 'Wait a moment, then press Try it', p: 'TMPlayer notices when the picture did not start ("the picture did not start on this device, only the sound") or when the sound did not, and starts the video again another way by itself. If it shows Try it instead, press it.' },
      { h: 'Try the other decoder', p: 'In the player\'s menu choose Try the other decoder. For a picture that is green, blocky or frozen, you can also set Decoder to Prefer software video in Settings, Playback, so the app\'s own decoder draws it.' },
      { h: 'For silence, pick another sound track', p: 'Open the sound track picker (the headphones) and choose another track: some videos carry a sound format the device cannot play as the first track, and a second track plays. Check the volume too.' },
    ],
    whyH: 'What TMPlayer does by itself',
    why: [
      'Android decoders often fail without saying so, so the app watches for a first picture and for a picture that stops, rather than waiting for an error.',
      'It then switches that one video to another decoder, and if needed to another player built into the app. Never more than twice per video, so it never loops.',
      'What worked is remembered for this kind of device, so the next video of that kind starts the working way at once.',
      'Videos from a chat that does not allow saving never go to another app; they stay in TMPlayer.',
    ],
    stillH: 'When to send logs',
    still: 'If both tries fail, or the same kind of video fails every time, open Settings, then Send logs, so the developer can see the format, the decoder and what was tried. It shows what would be sent first, with names and file names taken out. Say your phone or TV model, in the Telegram group or on GitHub.',
    updated: '2026-11-16',
  },
  {
    slug: 'nothing-in-home',
    crumb: 'Nothing in Home',
    title: 'Nothing shows in Home or a chat has no videos',
    description: 'Home is empty or a chat shows no videos in TMPlayer: choose the chats, check the smallest size shown, and what offline looks like. Three steps.',
    h1: ['Nothing shows', 'in Home'],
    lede: 'Home is empty, or a chat you know holds videos shows few or none. TMPlayer shows only the chats you chose, and hides very small videos unless you say otherwise.',
    icon: 'question-card',
    card: 'Choose the chats, check the smallest size shown, and what offline looks like.',
    steps: [
      { h: 'Choose the chats that hold videos', p: 'Home shows videos only from the chats you picked when you set it up. Open Chats and use Choose chats to tick more. Nothing changes in Telegram. No video yet? Forward one to Saved Messages in Telegram, then open Saved Messages in TMPlayer.' },
      { h: 'Check the smallest size shown', p: 'A line says how many videos are hidden by their size ("Videos under 400 MB are not on Home"). Open Settings and change Smallest video shown to a lower size. Short clips and phone videos are small, so they come back.' },
      { h: 'Look at the connection', p: 'With no network TMPlayer says "Offline", Home shows On this phone first (what is downloaded or already kept) and greys the rest as "Needs internet". It comes back by itself when the line does. If the line is up and it says it cannot reach Telegram, use the guide on a blocked network.' },
    ],
    whyH: 'What TMPlayer does by itself',
    why: [
      'It shows the chats as they were last time while Telegram answers, so Home is not blank on a slow start.',
      'Offline, it puts what you can play without a line at the top, and a greyed card opens its page and says to connect or download it first, instead of doing nothing.',
      'It does not look at chats you did not choose, so a chat missing from Home is one to choose, not one that is lost.',
    ],
    stillH: 'When to send logs',
    still: 'If a chat is chosen, the smallest size is low and the line is fine but videos you can see in Telegram are missing, open Settings, then Send logs, and say the chat\'s kind (a group, a channel) without its name. A chat that does not allow saving may hide its videos from any app.',
    updated: '2026-11-16',
  },
  {
    slug: 'telegram-sign-in-problems',
    crumb: 'Sign in problems',
    title: 'Telegram sign in problems: no code, wrong code, password',
    description: 'You cannot sign in to TMPlayer with Telegram: the code does not arrive, says wrong, or asks for a password. Three steps, the QR code way in, and what never to share.',
    h1: ['Cannot sign in', 'with Telegram'],
    lede: 'The code does not come, it says "Wrong code", or it asks for a password you do not remember. TMPlayer signs in to your own Telegram account, the way Telegram\'s own apps do.',
    icon: 'question-card',
    card: 'No code, a wrong code, a forgotten password, and the QR code way in.',
    steps: [
      { h: 'Look for the code in Telegram, not in your texts', p: 'If Telegram is open on another device, the code arrives there, in the chat from Telegram itself. Type the number with its country code, as in +44 or +91. Wait for the countdown, then choose another way to send it (a text, a call or the Telegram app).' },
      { h: 'Use the QR code instead', p: 'On a phone, choose Scan a QR code. On a TV the QR code is the first screen: on your phone open Telegram, go to Settings, then Devices, tap Link Desktop Device and point the camera at the TV. A number Telegram will not send a code to here signs in this way too.' },
      { h: 'The password and the wait', p: 'Two-step verification is your own Telegram password, not the code. If you forgot it, use Forgot password to get a recovery code by email. Too many tries makes Telegram say "try again in" some minutes or hours: wait, and do not keep pressing.' },
    ],
    whyH: 'What to know',
    why: [
      'TMPlayer is unofficial and not made by Telegram, but the sign in is Telegram\'s own: the code and password go to Telegram, not to TMPlayer, which has no server of its own.',
      'Resetting an account deletes every chat and message in it for good. Use it only as the last way back in.',
      'If Telegram never answers, the network may be blocking it: the guide on a blocked network covers proxies.',
      'Never post your login code or your password anywhere. Nobody from TMPlayer will ask for them.',
    ],
    stillH: 'When to send logs',
    still: 'If the sign in screen shows an error that is not one of the above, or sits on "Getting ready" for more than a minute with a good connection, use Save logs on that screen or Report on GitHub, and say what the screen said. Do not post the code, the password or your number.',
    updated: '2026-11-16',
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
      { h: 'Recognise it, and try another network', p: 'The screen says "Telegram may be blocked on this network. A proxy can get you through." when the connection never completes. If Telegram\'s own app is blocked in the same place, the network is the cause. Switch the phone to mobile data, or the TV to a hotspot, for the sign in: once signed in, TMPlayer keeps working on the same account.' },
      { h: 'Add a proxy', p: 'Choose Use a proxy on the sign in screen, or open Settings, Proxy. Paste a proxy link (it starts with t.me/proxy, t.me/socks, tg://proxy or tg://socks), or enter the server and port yourself. MTProto needs its secret too; SOCKS5 and HTTP may take a user name and password. Test shows how fast it answers, and "No answer from this proxy" means try another one.' },
      { h: 'Turn it off when you are home', p: 'Choose Stop using proxy in Settings, Proxy, and TMPlayer connects directly again.' },
    ],
    whyH: 'Worth knowing',
    why: [
      'When the network is up but Telegram does not answer, TMPlayer shows "Can\'t reach Telegram right now" with Retry and Use a proxy, and keeps trying by itself.',
      'Only use a proxy you trust. It can see that you use Telegram, but not your messages or videos.',
      'TMPlayer has no server of its own and runs no proxy. A proxy link comes from somewhere else, such as a channel that shares them.',
      'A proxy only helps the connection to Telegram. A video still plays from Telegram\'s own servers, so a slow proxy means a slow start.',
    ],
    stillH: 'When to send logs',
    still: 'If it still will not connect, say which network it is (a country, a school, a hotel) and what the screen says, in the Telegram group or on GitHub, with the logs from Save logs on that screen. Do not post a proxy secret or your login code.',
    updated: '2026-11-16',
  },
  {
    slug: 'phone-storage-full',
    crumb: 'Downloads stopped or space full',
    title: 'Downloads stopped or storage full: keep watching',
    description: 'A download that stopped, or a full phone or TV stick: resume it in Downloads, switch to Save space, free up space in TMPlayer without touching Telegram, and avoid filling up again.',
    h1: ['Downloads stopped', 'or storage is full'],
    lede: 'A download sits at Paused or Waiting, says "Not enough space", or a video will not play because the device is full. On a device that is nearly full, three steps keep it going.',
    icon: 'play-card',
    card: 'Resume a download, Free up space, Save space and the warning before a device runs out.',
    steps: [
      { h: 'Open Downloads and read the line', p: 'The Downloads tab says why a download is not moving: Paused, Waiting for Wi-Fi, Waiting for a connection, or Paused while a video plays. Press Resume, or Resume all. One that failed says so: press Try again.' },
      { h: 'Free up space', p: 'Press Free up space in Downloads to see what TMPlayer is holding, and remove what you have watched. Videos you played stay as Auto-saved until you remove them, or press Keep to hold on to one. Nothing is removed from Telegram: you can play or download any of it again.' },
      { h: 'Set While streaming to Save space', p: 'In Settings, under While streaming, choose Save space. Videos then stream with only a small part kept on the device instead of the whole file, and your downloads stay as they are. Seeking back downloads that part again, with a short pause now and then.' },
    ],
    whyH: 'What TMPlayer does by itself',
    why: [
      'It checks the free space before a download and says how much more it needs, instead of filling the device and stopping part way. A cut download leaves no half file behind.',
      'When a video does not fit, it offers to switch to Save space and play it.',
      'It pauses downloads while a video plays, and picks them up again afterwards.',
      'It warns, with the free space in the message, before the device runs out, so you clear space before a video stalls.',
      'On mobile data it asks before a large download, unless you set it to Never or Allow.',
    ],
    stillH: 'When to send logs',
    still: 'If a download keeps stopping with plenty of space and a good connection, open Settings, then Send logs, and say how large the video is and where downloads are saved (an SD card can be removed or full). A phone\'s own storage settings show what else is using it.',
    updated: '2026-11-16',
  },
];
