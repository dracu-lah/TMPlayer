import { api, onWorkerRestart } from '../tg/rpc'
import { touches } from '../store'
import { touchSender } from '../ui/touchSend'

const sender = touchSender({ read: touches, send: (version, ids, times) => api.setTouches(version, ids, times) })
onWorkerRestart(sender.reset)

/** The version of the Last played times the worker holds, sent once per change; a chat's view and the watch page name it. */
export const ensureTouches = sender.ensure
