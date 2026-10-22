package com.middleearth.launcher

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lembas's own adb client, for the two jobs that need one:
 *  - once, on the phone itself (over the phone's Wireless debugging), to give Lembas the
 *    permission to set the system colours and dark theme;
 *  - on the watch (over the watch's Wireless debugging), to install and update Lembas there.
 *
 * Pairing works like a computer pairing with the phone: Android shows a six-digit code, Lembas
 * sends it, and Android remembers Lembas's key from then on. The key lives only in Lembas's
 * private storage.
 */
class AdbLink private constructor(private val ctx: Context, private val keyName: String) : AbsAdbConnectionManager() {

    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(15, TimeUnit.SECONDS)
        val keyFile = File(ctx.filesDir, "$keyName.key")
        val certFile = File(ctx.filesDir, "$keyName.crt")
        var k: PrivateKey? = null
        var c: Certificate? = null
        if (keyFile.exists() && certFile.exists()) try {
            k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            c = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } catch (_: Exception) { k = null; c = null }
        if (k == null || c == null) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Lembas")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000L),
                Date(now + 30L * 365 * 86_400_000L), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            k = pair.private
            c = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(k.encoded)
            certFile.writeBytes(c.encoded)
        }
        key = k
        cert = c
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Lembas"

    /** Run a shell command and return what it printed. */
    fun shell(command: String): String {
        val stream: AdbStream = openStream("shell:$command")
        return stream.openInputStream().use { readAll(it) }.also { try { stream.close() } catch (_: Exception) { } }
    }

    /** Stream an APK to the device and install it (as an update if it's already there). */
    fun install(apk: File): String {
        val stream = openStream("exec:cmd package install -r -S ${apk.length()}")
        stream.openOutputStream().use { out -> apk.inputStream().use { it.copyTo(out, 64 * 1024) } }
        return stream.openInputStream().use { readAll(it) }.also { try { stream.close() } catch (_: Exception) { } }
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = try { input.read(buf) } catch (_: Exception) { -1 }
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    companion object {
        @Volatile private var self: AdbLink? = null
        @Volatile private var watch: AdbLink? = null

        /** The link to this phone's own adb. */
        fun phone(ctx: Context): AdbLink = self ?: synchronized(this) { self ?: AdbLink(ctx.applicationContext, "adb-phone").also { self = it } }

        /** A link to the watch (a separate key, so either can be forgotten on its own). */
        fun watch(ctx: Context): AdbLink = watch ?: synchronized(this) { watch ?: AdbLink(ctx.applicationContext, "adb-watch").also { watch = it } }

        /** Find a service Android advertises on the local network (the pairing port, while the code is showing). */
        fun discover(ctx: Context, type: String, timeoutMs: Long): Pair<InetAddress, Int>? {
            var found: Pair<InetAddress, Int>? = null
            val latch = CountDownLatch(1)
            val mdns = AdbMdns(ctx, type) { host, port ->
                if (host != null && port > 0 && found == null) { found = host to port; latch.countDown() }
            }
            mdns.start()
            try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } finally { mdns.stop() }
            return found
        }

        const val PAIRING = AdbMdns.SERVICE_TYPE_TLS_PAIRING
    }
}
