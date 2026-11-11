package com.itiel.smoothgps

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** מסך הסבר על ההרשאה ל-Health Connect. */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Smooth GPS כותבת ל-Health Connect רק נתוני צעדים, ורק כשהאפשרות \"דמה צעדים\" דולקה. " +
                "הנתונים נשמרים במכשיר שלך בלבד ואינם נשלחים לשום שרת."
            textSize = 16f
            setPadding(48, 48, 48, 48)
        })
    }
}
