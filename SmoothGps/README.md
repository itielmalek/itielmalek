# Smooth GPS (Android)

אפליקציית Fake GPS שמדמה **תנועה רציפה**: גוררים את הסיכה על המפה והמיקום המדומה *הולך* אליה במהירות שנבחרה (הליכה/ריצה/אופניים/רכב או סליידר 1–120 קמ"ש) – עדכון כל שנייה, עם bearing, speed, האטה בקרבת היעד ורעש GPS קטן. בלי קפיצות.

## הרצה
1. צור מפתח **Maps SDK for Android** ב-Google Cloud והוסף ל-`local.properties`:
   `MAPS_API_KEY=xxxx` (או סוד `MAPS_API_KEY` ב-GitHub לבניית APK ב-Actions).
2. פתח את התיקייה ב-Android Studio ← Run (או `gradle :app:assembleDebug`).
3. בטלפון: הגדרות ← אפשרויות מפתחים ← **Select mock location app** ← Smooth GPS (הכפתור "הגדרות מפתחים" באפליקציה פותח את המסך).
4. באפליקציה: גרור את הסיכה למקום ההתחלה ← **התחל** ← גרור את הסיכה, והמיקום (נקודה כחולה) ילך אליה.

## מבנה
- `MockLocationService` – Foreground Service; לולאה של שנייה: חישוב צעד לפי מהירות, bearing, `setTestProviderLocation` ל-GPS ול-NETWORK.
- `MainActivity` – מפה, סיכה נגררת (יעד), אנימציה חלקה של המיקום הנוכחי.
- `MockState` – מצב משותף (StateFlow).

הערה: אפליקציות מסוימות מזהות מיקום מדומה (`isMock`) – זה מחוץ ליכולת של אפליקציה ללא root.
