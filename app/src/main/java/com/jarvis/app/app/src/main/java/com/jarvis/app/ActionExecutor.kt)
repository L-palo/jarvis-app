package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager

class ActionExecutor(private val context: Context) {

    fun execute(decision: JarvisDecision): String {
        return when (decision.action) {
            "call" -> makeCall(decision.target)
            "text" -> sendText(decision.target, decision.message)
            "open_app" -> openApp(decision.target)
            "search" -> webSearch(decision.target)
            else -> "ok"
        }
    }

    private fun findContactNumber(name: String): String? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                return it.getString(numberIndex)
            }
        }
        return null
    }

    private fun makeCall(target: String): String {
        val number = findContactNumber(target)
        if (number == null) return "I couldn't find $target in your contacts."
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return "Calling $target."
    }

    private fun sendText(target: String, message: String): String {
        val number = findContactNumber(target)
        if (number == null) return "I couldn't find $target in your contacts."
        val smsManager = context.getSystemService(SmsManager::class.java)
        smsManager.sendTextMessage(number, null, message, null, null)
        return "Sent to $target."
    }

    private fun openApp(target: String): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(target, ignoreCase = true)
        }
        if (match == null) return "I couldn't find an app called $target."
        val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)
            return "Opening ${pm.getApplicationLabel(match)}."
        }
        return "I found $target but couldn't launch it."
    }

    private fun webSearch(query: String): String {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return "Searching for $query."
    }
}
