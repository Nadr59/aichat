 #!/bin/bash

#==========================================
# نسخ ملفات من UniversalDocExtractor إلى aichat
#==========================================

SRC="$HOME/-UniversalDocExtractor"
DST="$HOME/aichat"

echo "========================================="
echo "  نسخ ملفات المشروع"
echo "========================================="
echo ""
echo "المصدر: $SRC"
echo "الوجهة: $DST"
echo ""

#------------------------------------------
# 1. التحقق من وجود المجلدات
#------------------------------------------
if [ ! -d "$SRC" ]; then
    echo "❌ مجلد المصدر غير موجود!"
    echo "جاري الاستنساخ..."
    cd ~
    git clone https://github.com/Nadr59/-UniversalDocExtractor.git
fi

if [ ! -d "$DST" ]; then
    echo "❌ مجلد الوجهة غير موجود!"
    echo "جاري الاستنساخ..."
    cd ~
    git clone https://github.com/Nadr59/aichat.git
fi

cd "$DST"

echo ""
echo "========================================="
echo "[1/8] نسخ ملفات Gradle (الجذر)..."
echo "========================================="

# الملفات الموجودة في الجذر
for file in build.gradle.kts settings.gradle.kts gradle.properties gradlew gradlew.bat .gitignore; do
    if [ -f "$SRC/$file" ]; then
        if [ -f "$DST/$file" ]; then
            echo "  ⏭️  $file موجود بالفعل - تم التخطي"
        else
            cp "$SRC/$file" "$DST/$file"
            echo "  ✅ $file تم النسخ"
        fi
    else
        echo "  ⚠️  $file غير موجود في المصدر"
    fi
done

echo ""
echo "========================================="
echo "[2/8] نسخ مجلد gradle wrapper..."
echo "========================================="

mkdir -p gradle/wrapper

if [ -d "$SRC/gradle/wrapper" ]; then
    cp -r "$SRC/gradle/wrapper/"* gradle/wrapper/
    echo "  ✅ gradle/wrapper/* تم النسخ"
else
    echo "  ⚠️  gradle/wrapper غير موجود في المصدر"
fi

chmod +x gradlew

echo ""
echo "========================================="
echo "[3/8] نسخ ملفات app/build.gradle.kts..."
echo "========================================="

mkdir -p app

if [ -f "$SRC/app/build.gradle.kts" ]; then
    if [ -f "$DST/app/build.gradle.kts" ]; then
        echo "  ⏭️  app/build.gradle.kts موجود بالفعل"
    else
        cp "$SRC/app/build.gradle.kts" app/
        echo "  ✅ app/build.gradle.kts تم النسخ"
    fi
fi

if [ -f "$SRC/app/proguard-rules.pro" ]; then
    if [ -f "$DST/app/proguard-rules.pro" ]; then
        echo "  ⏭️  app/proguard-rules.pro موجود بالفعل"
    else
        cp "$SRC/app/proguard-rules.pro" app/
        echo "  ✅ app/proguard-rules.pro تم النسخ"
    fi
fi

echo ""
echo "========================================="
echo "[4/8] نسخ ملفات res..."
echo "========================================="

mkdir -p app/src/main/res

# نسخ المجلدات الفرعية لـ res
RES_DIRS="drawable drawable-hdpi drawable-mdpi drawable-xhdpi drawable-xxhdpi drawable-xxxhdpi mipmap-hdpi mipmap-mdpi mipmap-xhdpi mipmap-xxhdpi mipmap-xxxhdpi mipmap-anydpi-v26 xml font layout menu values-night"

for dir in $RES_DIRS; do
    if [ -d "$SRC/app/src/main/res/$dir" ]; then
        if [ -d "$DST/app/src/main/res/$dir" ]; then
            echo "  ⏭️  res/$dir موجود بالفعل"
        else
            cp -r "$SRC/app/src/main/res/$dir" app/src/main/res/
            echo "  ✅ res/$dir تم النسخ"
        fi
    fi
done

# نسخ ملفات res/valuesRemaining
for file in $(ls "$SRC/app/src/main/res/values/" 2>/dev/null); do
    if [ ! -f "$DST/app/src/main/res/values/$file" ]; then
        cp "$SRC/app/src/main/res/values/$file" app/src/main/res/values/
        echo "  ✅ res/values/$file تم النسخ"
    else
        echo "  ⏭️  res/values/$file موجود بالفعل"
    fi
done

echo ""
echo "========================================="
echo "[5/8] نسخ ملف .github/workflows..."
echo "========================================="

if [ -d "$SRC/.github/workflows" ]; then
    mkdir -p .github/workflows
    for file in "$SRC/.github/workflows/"*; do
        filename=$(basename "$file")
        if [ -f "$DST/.github/workflows/$filename" ]; then
            echo "  ⏭️  .github/workflows/$filename موجود بالفعل"
        else
            cp "$file" .github/workflows/
            echo "  ✅ .github/workflows/$filename تم النسخ"
        fi
    done
else
    echo "  ⚠️  .github/workflows غير موجود في المصدر"
fi

echo ""
echo "========================================="
echo "[6/8] نسخ ملفات أخرى..."
echo "========================================="

# نسخ أي ملفات إضافية في app/src/main غير java و res
for file in "$SRC/app/src/main/"*; do
    filename=$(basename "$file")
    if [ "$filename" != "java" ] && [ "$filename" != "res" ]; then
        if [ -f "$file" ]; then
            if [ ! -f "$DST/app/src/main/$filename" ]; then
                cp "$file" app/src/main/
                echo "  ✅ app/src/main/$filename تم النسخ"
            else
                echo "  ⏭️  app/src/main/$filename موجود بالفعل"
            fi
        fi
    fi
done

echo ""
echo "========================================="
echo "[7/8] جعل gradlew قابل للتنفيذ..."
echo "========================================="

chmod +x gradlew
chmod +x gradlew.bat 2>/dev/null
echo "  ✅ تم"

echo ""
echo "========================================="
echo "[8/8] التحقق النهائي..."
echo "========================================="

echo ""
echo "📁 هيكل المشروع:"
echo "-----------------------------------------"
find . -type f \
    ! -path "./.git/*" \
    ! -path "./.github/*" \
    | sort

echo ""
echo "-----------------------------------------"
echo "📊 إحصائيات:"
echo "  إجمالي الملفات: $(find . -type f ! -path "./.git/*" | wc -l)"
echo "  ملفات Kotlin: $(find . -name "*.kt" | wc -l)"
echo "  ملفات XML: $(find . -name "*.xml" | wc -l)"
echo "  ملفات Gradle: $(find . -name "*.gradle.kts" | wc -l)"
echo "-----------------------------------------"
echo ""
echo "✅ اكتملت النسخ بنجاح!"
echo ""
