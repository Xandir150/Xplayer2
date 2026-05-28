package com.teleteh.xplayer2.data.glasses

/**
 * MCU protocol constants and utilities for XREAL Air series glasses.
 * Ported from xspace (which adapted them from nrealAirLinuxDriver).
 *
 * Used by [GlassesController] to switch the connected glasses between
 * 2D and 3D SBS display modes from the phone UI without making the
 * user open the glasses' own menu.
 */
object GlassesProtocol {
    const val MCU_PACKET_HEAD = 0xFD.toByte()
    const val MCU_PACKET_SIZE = 64
    const val MCU_MSG_P_BUTTON_PRESSED = 0x6C05
    const val MCU_MSG_R_BRIGHTNESS = 0x03
    const val MCU_MSG_W_BRIGHTNESS = 0x04
    const val MCU_MSG_R_DISP_MODE = 0x07
    const val MCU_MSG_W_DISP_MODE = 0x08
    
    // Interface ID for MCU (может быть 4 или 6 в зависимости от модели)
    const val MCU_INTERFACE_ID = 6  // Для XREAL Air 2 Pro это интерфейс 6
    
    // Virtual button IDs
    const val MCU_BUTTON_VIRT_DISPLAY_TOGGLE = 0x01
    const val MCU_BUTTON_VIRT_BRIGHTNESS_UP = 0x06
    const val MCU_BUTTON_VIRT_BRIGHTNESS_DOWN = 0x07
    const val MCU_BUTTON_VIRT_UP = 0x08
    const val MCU_BUTTON_VIRT_DOWN = 0x09
    const val MCU_BUTTON_VIRT_MODE_2D = 0x0A
    const val MCU_BUTTON_VIRT_MODE_3D = 0x0B
    const val MCU_BUTTON_VIRT_BLEND_CYCLE = 0x0C
    const val MCU_BUTTON_VIRT_CONTROL_TOGGLE = 0x0F
    
    // Display modes
    const val MCU_DISPLAY_MODE_1920x1080_60 = 0x01
    const val MCU_DISPLAY_MODE_3840x1080_60_SBS = 0x03
    const val MCU_DISPLAY_MODE_3840x1080_72_SBS = 0x04
    const val MCU_DISPLAY_MODE_1920x1080_72 = 0x05
    const val MCU_DISPLAY_MODE_1920x1080_60_SBS = 0x08
    const val MCU_DISPLAY_MODE_3840x1080_90_SBS = 0x09
    const val MCU_DISPLAY_MODE_1920x1080_90 = 0x0A
    const val MCU_DISPLAY_MODE_1920x1080_120 = 0x0B
    
    // CRC32 table (полная таблица 256 элементов)
    private val CRC32_TABLE = intArrayOf(
        0x00000000.toInt(), 0x77073096.toInt(), 0xEE0E612C.toInt(), 0x990951BA.toInt(),
        0x076DC419, 0x706AF48F.toInt(), 0xE963A535.toInt(), 0x9E6495A3.toInt(),
        0x0EDB8832, 0x79DCB8A4, 0xE0D5E91E.toInt(), 0x97D2D988.toInt(),
        0x09B64C2B, 0x7EB17CBD, 0xE7B82D07.toInt(), 0x90BF1D91.toInt(),
        0x1DB71064, 0x6AB020F2, 0xF3B97148.toInt(), 0x84BE41DE.toInt(),
        0x1ADAD47D, 0x6DDDE4EB, 0xF4D4B551.toInt(), 0x83D385C7.toInt(),
        0x136C9856, 0x646BA8C0, 0xFD62F97A.toInt(), 0x8A65C9EC.toInt(),
        0x14015C4F, 0x63066CD9, 0xFA0F3D63.toInt(), 0x8D080DF5.toInt(),
        0x3B6E20C8, 0x4C69105E, 0xD56041E4.toInt(), 0xA2677172.toInt(),
        0x3C03E4D1, 0x4B04D447, 0xD20D85FD.toInt(), 0xA50AB56B.toInt(),
        0x35B5A8FA, 0x42B2986C, 0xDBBBC9D6.toInt(), 0xACBCF940.toInt(),
        0x32D86CE3, 0x45DF5C75, 0xDCD60DCF.toInt(), 0xABD13D59.toInt(),
        0x26D930AC, 0x51DE003A, 0xC8D75180.toInt(), 0xBFD06116.toInt(),
        0x21B4F4B5, 0x56B3C423, 0xCFBA9599.toInt(), 0xB8BDA50F.toInt(),
        0x2802B89E, 0x5F058808, 0xC60CD9B2.toInt(), 0xB10BE924.toInt(),
        0x2F6F7C87, 0x58684C11, 0xC1611DAB.toInt(), 0xB6662D3D.toInt(),
        0x76DC4190, 0x01DB7106, 0x98D220BC.toInt(), 0xEFD5102A.toInt(),
        0x71B18589, 0x06B6B51F, 0x9FBFE4A5.toInt(), 0xE8B8D433.toInt(),
        0x7807C9A2, 0x0F00F934.toInt(), 0x9609A88E.toInt(), 0xE10E9818.toInt(),
        0x7F6A0DBB, 0x086D3D2D, 0x91646C97.toInt(), 0xE6635C01.toInt(),
        0x6B6B51F4, 0x1C6C6162, 0x856530D8.toInt(), 0xF262004E.toInt(),
        0x6C0695ED, 0x1B01A57B, 0x8208F4C1.toInt(), 0xF50FC457.toInt(),
        0x65B0D9C6, 0x12B7E950, 0x8BBEB8EA.toInt(), 0xFCB9887C.toInt(),
        0x62DD1DDF, 0x15DA2D49, 0x8CD37CF3.toInt(), 0xFBD44C65.toInt(),
        0x4DB26158, 0x3AB551CE, 0xA3BC0074.toInt(), 0xD4BB30E2.toInt(),
        0x4ADFA541, 0x3DD895D7, 0xA4D1C46D.toInt(), 0xD3D6F4FB.toInt(),
        0x4369E96A, 0x346ED9FC, 0xAD678846.toInt(), 0xDA60B8D0.toInt(),
        0x44042D73, 0x33031DE5, 0xAA0A4C5F.toInt(), 0xDD0D7CC9.toInt(),
        0x5005713C, 0x270241AA, 0xBE0B1010.toInt(), 0xC90C2086.toInt(),
        0x5768B525, 0x206F85B3, 0xB966D409.toInt(), 0xCE61E49F.toInt(),
        0x5EDEF90E, 0x29D9C998, 0xB0D09822.toInt(), 0xC7D7A8B4.toInt(),
        0x59B33D17, 0x2EB40D81, 0xB7BD5C3B.toInt(), 0xC0BA6CAD.toInt(),
        0xEDB88320.toInt(), 0x9ABFB3B6.toInt(), 0x03B6E20C.toInt(), 0x74B1D29A.toInt(),
        0xEAD54739.toInt(), 0x9DD277AF.toInt(), 0x04DB2615, 0x73DC1683,
        0xE3630B12.toInt(), 0x94643B84.toInt(), 0x0D6D6A3E, 0x7A6A5AA8,
        0xE40ECF0B.toInt(), 0x9309FF9D.toInt(), 0x0A00AE27, 0x7D079EB1,
        0xF00F9344.toInt(), 0x8708A3D2.toInt(), 0x1E01F268, 0x6906C2FE,
        0xF762575D.toInt(), 0x806567CB.toInt(), 0x196C3671, 0x6E6B06E7,
        0xFED41B76.toInt(), 0x89D32BE0.toInt(), 0x10DA7A5A, 0x67DD4ACC,
        0xF9B9DF6F.toInt(), 0x8EBEEFF9.toInt(), 0x17B7BE43, 0x60B08ED5,
        0xD6D6A3E8.toInt(), 0xA1D1937E.toInt(), 0x38D8C2C4, 0x4FDFF252,
        0xD1BB67F1.toInt(), 0xA6BC5767.toInt(), 0x3FB506DD, 0x48B2364B,
        0xD80D2BDA.toInt(), 0xAF0A1B4C.toInt(), 0x36034AF6, 0x41047A60,
        0xDF60EFC3.toInt(), 0xA867DF55.toInt(), 0x316E8EEF, 0x4669BE79,
        0xCB61B38C.toInt(), 0xBC66831A.toInt(), 0x256FD2A0, 0x5268E236,
        0xCC0C7795.toInt(), 0xBB0B4703.toInt(), 0x220216B9, 0x5505262F,
        0xC5BA3BBE.toInt(), 0xB2BD0B28.toInt(), 0x2BB45A92, 0x5CB36A04,
        0xC2D7FFA7.toInt(), 0xB5D0CF31.toInt(), 0x2CD99E8B, 0x5BDEAE1D,
        0x9B64C2B0.toInt(), 0xEC63F226.toInt(), 0x756AA39C, 0x026D930A,
        0x9C0906A9.toInt(), 0xEB0E363F.toInt(), 0x72076785, 0x05005713,
        0x95BF4A82.toInt(), 0xE2B87A14.toInt(), 0x7BB12BAE, 0x0CB61B38,
        0x92D28E9B.toInt(), 0xE5D5BE0D.toInt(), 0x7CDCEFB7, 0x0BDBDF21,
        0x86D3D2D4.toInt(), 0xF1D4E242.toInt(), 0x68DDB3F8, 0x1FDA836E,
        0x81BE16CD.toInt(), 0xF6B9265B.toInt(), 0x6FB077E1, 0x18B74777,
        0x88085AE6.toInt(), 0xFF0F6A70.toInt(), 0x66063BCA, 0x11010B5C,
        0x8F659EFF.toInt(), 0xF862AE69.toInt(), 0x616BFFD3, 0x166CCF45,
        0xA00AE278.toInt(), 0xD70DD2EE.toInt(), 0x4E048354, 0x3903B3C2,
        0xA7672661.toInt(), 0xD06016F7.toInt(), 0x4969474D, 0x3E6E77DB,
        0xAED16A4A.toInt(), 0xD9D65ADC.toInt(), 0x40DF0B66, 0x37D83BF0,
        0xA9BCAE53.toInt(), 0xDEBB9EC5.toInt(), 0x47B2CF7F, 0x30B5FFE9,
        0xBDBDF21C.toInt(), 0xCABAC28A.toInt(), 0x53B39330, 0x24B4A3A6,
        0xBAD03605.toInt(), 0xCDD70693.toInt(), 0x54DE5729, 0x23D967BF,
        0xB3667A2E.toInt(), 0xC4614AB8.toInt(), 0x5D681B02, 0x2A6F2B94,
        0xB40BBE37.toInt(), 0xC30C8EA1.toInt(), 0x5A05DF1B, 0x2D02EF8D
    )
    
    /**
     * Calculate CRC32 checksum (точная копия из nrealAirLinuxDriver)
     */
    fun calculateCrc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            val t = (crc xor (b.toInt() and 0xFF)) and 0xFF
            crc = ((crc ushr 8) and 0xFFFFFF) xor CRC32_TABLE[t]
        }
        return crc.inv()
    }
    
    /**
     * Get display mode name
     */
    fun getDisplayModeName(mode: Int): String {
        return when (mode) {
            MCU_DISPLAY_MODE_1920x1080_60 -> "2D 60Hz"
            MCU_DISPLAY_MODE_1920x1080_72 -> "2D 72Hz"
            MCU_DISPLAY_MODE_1920x1080_90 -> "2D 90Hz"
            MCU_DISPLAY_MODE_1920x1080_120 -> "2D 120Hz"
            MCU_DISPLAY_MODE_3840x1080_60_SBS -> "3D SBS 60Hz"
            MCU_DISPLAY_MODE_3840x1080_72_SBS -> "3D SBS 72Hz"
            MCU_DISPLAY_MODE_3840x1080_90_SBS -> "3D SBS 90Hz"
            else -> "Unknown (0x${mode.toString(16)})"
        }
    }
    
    /**
     * Check if display mode is 3D
     */
    fun is3DMode(mode: Int): Boolean {
        return mode in listOf(
            MCU_DISPLAY_MODE_3840x1080_60_SBS,
            MCU_DISPLAY_MODE_3840x1080_72_SBS,
            MCU_DISPLAY_MODE_3840x1080_90_SBS
        )
    }
}
