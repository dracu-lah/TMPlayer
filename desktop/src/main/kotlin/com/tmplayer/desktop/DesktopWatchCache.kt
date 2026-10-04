package com.tmplayer.desktop

import com.tmplayer.data.WatchCacheRules

/**
 * The desktop's watch cache: the shared [WatchCacheRules], built in [DesktopServices] with a byte
 * cap ([com.tmplayer.data.CacheRule.UnderCap], `cache_limit_bytes` in `desktop.properties`) in
 * place of the phone's one video, and with every file a player has open spared.
 */
typealias DesktopWatchCache = WatchCacheRules
