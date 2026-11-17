package android.os.storage

import java.io.File

/** One volume on an iPhone or iPad: the app's own container. */
class StorageManager {
    val storageVolumes: List<StorageVolume> get() = listOf(StorageVolume.Primary)
    val primaryStorageVolume: StorageVolume get() = StorageVolume.Primary
    fun getStorageVolume(file: File): StorageVolume? = StorageVolume.Primary
}

class StorageVolume private constructor() {
    val isPrimary: Boolean get() = true
    val isRemovable: Boolean get() = false
    val isEmulated: Boolean get() = true
    val uuid: String? get() = null
    val state: String get() = android.os.Environment.MEDIA_MOUNTED
    val directory: File? get() = android.os.Environment.getExternalStorageDirectory()
    fun getDescription(context: android.content.Context?): String = platform.UIKit.UIDevice.currentDevice.model

    companion object {
        internal val Primary = StorageVolume()
    }
}
