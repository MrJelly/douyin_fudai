package com.jelly.fudai

import android.util.Base64
import java.io.*
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SmtpPermanentException(message: String) : IOException(message)

object EmailNotifier {
    @JvmStatic fun send(sender: String, authCode: String, recipient: String, subject: String, body: String) {
        require(listOf(sender, recipient).all {
            !it.contains('\r') && !it.contains('\n') && it.matches(Regex("[^\\s<>@]+@[^\\s<>@]+"))
        }) { "邮箱地址格式错误" }
        val host = when {
            sender.endsWith("@qq.com", true) -> "smtp.qq.com"
            sender.endsWith("@163.com", true) -> "smtp.163.com"
            sender.endsWith("@126.com", true) -> "smtp.126.com"
            sender.endsWith("@139.com", true) -> "smtp.139.com"
            sender.endsWith("@gmail.com", true) -> "smtp.gmail.com"
            else -> error("不支持的发件邮箱")
        }
        val firstError = try {
            sendImplicitTls(host, 465, sender, authCode, recipient, subject, body)
            return
        } catch (error: Exception) {
            error
        }
        if (firstError is SmtpPermanentException) throw firstError
        val fallbackPort = 587
        try {
            sendStartTls(host, fallbackPort, sender, authCode, recipient, subject, body)
        } catch (error: Exception) {
            val first = "465=${firstError.javaClass.simpleName}: ${firstError.message}; "
            throw IOException("$first$fallbackPort=${error.javaClass.simpleName}: ${error.message}", error)
        }
    }

    private fun sendImplicitTls(host: String, port: Int, sender: String, authCode: String,
                                recipient: String, subject: String, body: String) {
        val transport = connect(host, port)
        try {
            ((SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(transport, host, port, true) as SSLSocket).use { socket ->
                socket.soTimeout = 15000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                val input = socket.inputStream.bufferedReader(StandardCharsets.UTF_8)
                val output = socket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                expect(input, 220, "连接欢迎语")
                sendMessage(output, input, sender, authCode, recipient, subject, body)
            }
        } finally {
            if (!transport.isClosed) transport.close()
        }
    }

    private fun sendStartTls(host: String, port: Int, sender: String, authCode: String,
                             recipient: String, subject: String, body: String) {
        val transport = connect(host, port)
        try {
            transport.soTimeout = 15000
            val plainInput = transport.inputStream.bufferedReader(StandardCharsets.UTF_8)
            val plainOutput = transport.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            expect(plainInput, 220, "连接欢迎语")
            command(plainOutput, plainInput, "EHLO fudai", 250, "EHLO")
            command(plainOutput, plainInput, "STARTTLS", 220, "STARTTLS")
            ((SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(transport, host, port, true) as SSLSocket).use { socket ->
                socket.soTimeout = 15000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                val input = socket.inputStream.bufferedReader(StandardCharsets.UTF_8)
                val output = socket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                sendMessage(output, input, sender, authCode, recipient, subject, body)
            }
        } finally {
            if (!transport.isClosed) transport.close()
        }
    }

    private fun connect(host: String, port: Int): Socket {
        return Socket().apply { connect(InetSocketAddress(host, port), 10000) }
    }

    private fun sendMessage(output: BufferedWriter, input: BufferedReader, sender: String,
                            authCode: String, recipient: String, subject: String, body: String) {
        command(output, input, "EHLO fudai", 250, "EHLO")
        command(output, input, "AUTH LOGIN", 334, "AUTH LOGIN")
        command(output, input, b64(sender), 334, "SMTP账号")
        command(output, input, b64(authCode), 235, "SMTP授权码")
        command(output, input, "MAIL FROM:<$sender>", 250, "发件人地址")
        command(output, input, "RCPT TO:<$recipient>", 250, "收件人地址")
        command(output, input, "DATA", 354, "邮件正文")
        val senderDomain = sender.substringAfter('@', "localhost")
        val messageId = "${System.currentTimeMillis()}.${UUID.randomUUID().toString().replace("-", "")}@$senderDomain"
        output.write("Date: ${SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())}\r\n")
        output.write("From: =?UTF-8?B?${b64("福袋提醒")}?= <$sender>\r\n")
        output.write("To: <$recipient>\r\n")
        output.write("Message-ID: <$messageId>\r\n")
        output.write("Subject: =?UTF-8?B?${b64(subject)}?=\r\n")
        output.write("MIME-Version: 1.0\r\n")
        output.write("Content-Type: text/plain; charset=UTF-8\r\n")
        output.write("Content-Transfer-Encoding: base64\r\n\r\n")
        Base64.encodeToString(body.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            .chunked(76)
            .forEach { output.write(it + "\r\n") }
        output.write(".\r\n")
        output.flush()
        expect(input, 250, "提交邮件")
        // DATA was accepted, so the message is sent. A server that closes before
        // replying to QUIT must not cause the outer retry loop to send a duplicate.
        try { command(output, input, "QUIT", 221, "QUIT") } catch (_: Exception) {}
    }
    private fun b64(v: String) = Base64.encodeToString(v.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    private fun command(out: BufferedWriter, input: BufferedReader, value: String, code: Int, stage: String) { out.write(value + "\r\n"); out.flush(); expect(input, code, stage) }
    private fun expect(input: BufferedReader, expected: Int, stage: String) {
        var line = input.readLine() ?: error("$stage：SMTP连接中断")
        val firstLine = line
        val actual = line.take(3).toInt()
        while (line.length > 3 && line[3] == '-') line = input.readLine() ?: break
        if (actual != expected) {
            val message = "$stage：SMTP返回 $actual ${firstLine.drop(4).take(160)}"
            if (actual in 500..599) throw SmtpPermanentException(message)
            error(message)
        }
    }
}
