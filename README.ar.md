<div dir="rtl">

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="88" height="88" alt="UmbraProxy">
</p>

<h1 align="center">UmbraProxy</h1>

<p align="center">بروكسي MTProto محلي لتطبيق تيليجرام على أندرويد، مبني باستخدام Kotlin وGo.</p>

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/English-475569?style=for-the-badge" alt="English"></a>
  <a href="README.ar.md"><img src="https://img.shields.io/badge/%D8%A7%D9%84%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9-2563EB?style=for-the-badge" alt="العربية"></a>
</p>

## التنزيل

يتطلب **أندرويد 8.0 أو أحدث**.

| النسخة | الجهاز المناسب | التنزيل |
| --- | --- | --- |
| ARM64 | معظم هواتف أندرويد الحديثة | [APK](https://github.com/RHineIx/UmbraProxy/releases/latest/download/UmbraProxy-arm64-v8a.apk) |
| ARMv7 | الأجهزة التي تعمل بأندرويد ARM بمعمارية 32 بت | [APK](https://github.com/RHineIx/UmbraProxy/releases/latest/download/UmbraProxy-armeabi-v7a.apk) |
| Universal | تشمل معماريتَي ARM، إذا لم تكن متأكداً من نسخة جهازك | [APK](https://github.com/RHineIx/UmbraProxy/releases/latest/download/UmbraProxy-universal.apk) |

[جميع الإصدارات وملفات التحقق](https://github.com/RHineIx/UmbraProxy/releases)

## الاستخدام

1. افتح UmbraProxy وشغّل البروكسي.
2. اضغط **فتح تيليجرام**.
3. وافق على الاتصال بالبروكسي داخل تيليجرام وتأكد من تحميل الرسائل.

يجب أن يكون تيليجرام مثبّتاً على الهاتف نفسه؛ يستمع البروكسي على العنوان `127.0.0.1`.

استخدم **أيقونة المظهر العائمة** لتغيير الثيم أو اختيار العربية والإنجليزية.
اضغط **رقم الإصدار** في الأسفل للتحقق من التحديثات وتنزيل النسخة المناسبة.

## المميزات

- بروكسي MTProto يدعم النقل عبر WebSocket، مع توجيه Cloudflare اختياري والرجوع إلى TCP.
- تمويه FakeTLS اختياري وإمكانية تخصيص مفتاح البروكسي.
- حالة الاتصال وعدد الجلسات وإحصاءات التنزيل والرفع مباشرةً.
- سجلات تدعم البحث وتصفية المستويات وإيقاف العرض ومتابعة الأحدث والتصدير بصيغتَي نص وJSONL.
- زر في الإعدادات السريعة، واستعادة الاتصال عند تغيّر الشبكة، وتشغيل اختياري بعد إعادة تشغيل الهاتف.
- مظهر فاتح وداكن وبحسب النظام، مع واجهة بالعربية والإنجليزية.

## البناء من المصدر

<details>
<summary>المتطلبات وأوامر البناء</summary>

استخدم **Linux أو macOS**، و**JDK 17**، و**Go 1.26**. أدوات أندرويد المطلوبة:
**SDK 35** و**Build Tools 36.0.0** و**NDK 27.2.12479018**.
اضبط `JAVA_HOME` على JDK 17، وأضف Go وأدوات سطر أوامر Android SDK إلى `PATH`.

<div dir="ltr">

```sh
git clone https://github.com/RHineIx/UmbraProxy.git
cd UmbraProxy

export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
sdkmanager 'platforms;android-35' 'build-tools;36.0.0' 'ndk;27.2.12479018'

bash scripts/build-native.sh
bash gradlew testDebugUnitTest lintDebug assembleDebug
```

</div>

ستجد ملفات APK التجريبية في `app/build/outputs/apk/debug/`.
أعد بناء المكتبات الأصلية بعد تعديل كود Go؛ لا يبنيها Gradle تلقائياً.

لتشغيل فحوص Go:

<div dir="ltr">

```sh
go vet ./...
go test -race ./...
```

</div>

</details>

## التواصل

[Telegram](https://t.me/RHineix) · [GitHub](https://github.com/RHineIx)

</div>
