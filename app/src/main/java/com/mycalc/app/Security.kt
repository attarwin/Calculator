package com.mycalc.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Flags that decide when the app may stay alive instead of closing itself. */
object Guard {
    /** True while the vault has handed control to the system file picker. */
    @Volatile
    var external = false

    @Volatile
    var leftAt = 0L
}

/** Secret passcode. Stored only as a salted PBKDF2 hash. Default is 1234 until the user changes it. */
object Passcode {
    private const val DEFAULT = "1234"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("calc_prefs", Context.MODE_PRIVATE)

    fun verify(ctx: Context, code: String): Boolean {
        val s = sp(ctx).getString("salt", null)
        val h = sp(ctx).getString("hash", null)
        if (s == null || h == null) return code == DEFAULT
        val salt = Base64.decode(s, Base64.NO_WRAP)
        val expected = Base64.decode(h, Base64.NO_WRAP)
        return MessageDigest.isEqual(hash(code, salt), expected)
    }

    fun set(ctx: Context, code: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        sp(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(code, salt), Base64.NO_WRAP))
            .apply()
    }

    fun isDefault(ctx: Context): Boolean = verify(ctx, DEFAULT)

    private fun hash(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), salt, 60000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

/** AES-256-GCM with a key that lives inside the Android Keystore. */
object VaultCrypto {
    private const val ALIAS = "calc_vault_key"
    private const val CHUNK = 1024 * 1024

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun enc(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    fun dec(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    fun encStr(s: String): String =
        Base64.encodeToString(enc(s.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    fun decStr(s: String): String =
        String(dec(Base64.decode(s, Base64.NO_WRAP)), Charsets.UTF_8)

    /** Encrypts in 1 MB blocks so big videos do not fill the phone memory. */
    fun encryptStream(input: InputStream, out: OutputStream) {
        val buf = ByteArray(CHUNK)
        val dos = DataOutputStream(out)
        while (true) {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n == 0) break
            val ct = enc(buf.copyOf(n))
            dos.writeInt(ct.size)
            dos.write(ct)
            if (n < buf.size) break
        }
        dos.flush()
    }

    fun decryptStream(input: InputStream, out: OutputStream) {
        val dis = DataInputStream(input)
        while (true) {
            val len = try {
                dis.readInt()
            } catch (e: EOFException) {
                break
            }
            val ct = ByteArray(len)
            dis.readFully(ct)
            out.write(dec(ct))
        }
        out.flush()
    }
}
