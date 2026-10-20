package android.util

object Base64 {
    const val NO_WRAP: Int = 2
    const val URL_SAFE: Int = 8
    const val DEFAULT: Int = 0
    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String =
        if ((flags and URL_SAFE) != 0) java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input)
        else java.util.Base64.getEncoder().encodeToString(input)
    @JvmStatic fun decode(input: String, flags: Int): ByteArray =
        if ((flags and URL_SAFE) != 0) java.util.Base64.getUrlDecoder().decode(input)
        else java.util.Base64.getDecoder().decode(input)
}

object Log {
    @JvmStatic fun w(tag: String, msg: String): Int { System.err.println("W/$tag $msg"); return 0 }
    @JvmStatic fun i(tag: String, msg: String): Int { System.err.println("I/$tag $msg"); return 0 }
}
