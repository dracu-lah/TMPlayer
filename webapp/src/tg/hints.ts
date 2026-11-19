import { rules } from '../rules'
import { createTitleNotes } from './hintsCore'

// The language words in a chat's title ("Malayalam Shows" gives "ml"), noted as the chat list is
// read so a video's poster question carries them as `hints` (HX24, ChatLanguageHints through the
// rules' noteChat). The rules load beside the connection, so a title that arrives first waits and
// is noted the moment they are in (hintsCore.ts). Bounded like the registry itself: a chat past
// that is simply not noted and its videos ask without hints.
export const { noteChatTitle, forgetChatTitles } = createTitleNotes(rules)
