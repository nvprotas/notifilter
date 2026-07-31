# Подписание release APK и AAB

Эта схема предназначена для прямой раздачи APK и публикации в нескольких
магазинах с собственным ключом разработчика. Все обновления одного
`applicationId` должны оставаться совместимыми по сертификату подписи, поэтому
release-ключ нельзя терять или незаметно заменять.

Workflow `Signed Release` состоит из двух изолированных job:

1. `build-unsigned` получает исходники, запускает тесты, lint и Gradle, но не
   имеет доступа к release-секретам;
2. `sign-release` не получает исходники и не запускает Gradle. Он скачивает
   проверенный artifact, подписывает APK/AAB стандартными Android/JDK tools,
   сверяет сертификат и удаляет временный keystore до загрузки результата.

Все Actions в этом workflow закреплены на полных commit SHA. GitHub Environment
`release-signing` разрешает deployment только из ветки `master`, а обход его
защит администратором отключён.

## 1. Создайте ключ на отдельной доверенной машине

Не создавайте production-ключ на малом сервере с репозиторием или на GitHub
runner. Выполните это на своей доверенной рабочей машине:

```bash
keytool -genkeypair -v \
  -keystore notifilter-release.jks \
  -storetype JKS \
  -alias notifilter \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Используйте уникальный длинный пароль. Запишите тип хранилища `JKS`, alias
`notifilter`, дату создания и место хранения паролей.

Получите публичный SHA-256 сертификата:

```bash
keytool -list -v \
  -keystore notifilter-release.jks \
  -storetype JKS \
  -alias notifilter
```

Сохраните значение строки `SHA256` офлайн. Двоеточия и регистр несущественны.
Можно также экспортировать публичный сертификат — он не содержит закрытого
ключа:

```bash
keytool -exportcert -rfc \
  -keystore notifilter-release.jks \
  -storetype JKS \
  -alias notifilter \
  -file notifilter-release-cert.pem
```

Не передавайте `.jks` или пароли через issue, чат, email либо Actions artifact.

## 2. Сделайте и проверьте бэкапы до GitHub

GitHub Secret — рабочая CI-копия с односторонней записью, а не резервная копия:
скачать его обратно нельзя. До загрузки в GitHub сделайте минимум две независимо
хранящиеся зашифрованные копии `notifilter-release.jks`, например:

1. зашифрованное вложение в доверенном менеджере паролей или корпоративном
   secrets vault;
2. отдельный зашифрованный USB-носитель в другом физическом месте.

Пароль или recovery-данные от второй копии не храните только на этом же
носителе. Не полагайтесь на две копии в одной учётной записи облака — это один
домен отказа.

После копирования расшифруйте каждую копию во временное место и для каждой
выполните:

```bash
keytool -list -v \
  -keystore /path/to/restored/notifilter-release.jks \
  -storetype JKS \
  -alias notifilter
```

Проверьте, что обе копии открываются и показывают тот же SHA-256. Вместе с
бэкапом сохраните:

- SHA-256 публичного сертификата;
- alias и тип `JKS`;
- место восстановления store/key passwords;
- SHA-256 самого файла `notifilter-release.jks` для контроля целостности.

Потеря ключа может лишить возможности обновлять уже установленные напрямую APK
и публикации в магазинах, использующих этот сертификат. До первой публикации
также выясните, не переподписывает ли конкретный магазин приложения своим
ключом: от этого зависит совместимость обновлений между каналами.

## 3. Загрузите рабочую копию только через `gh`

Команды выполняются на доверенной машине, где лежит keystore. Сначала проверьте,
что `gh` авторизован в правильной учётной записи:

```bash
gh auth status
```

Загрузите бинарный keystore как Base64 непосредственно в environment secret, не
сохраняя Base64 в промежуточный файл.

Linux:

```bash
base64 --wrap=0 notifilter-release.jks | \
  gh secret set ANDROID_SIGNING_KEYSTORE_BASE64 \
    --env release-signing \
    --repo nvprotas/notifilter
```

macOS:

```bash
base64 < notifilter-release.jks | tr -d '\n' | \
  gh secret set ANDROID_SIGNING_KEYSTORE_BASE64 \
    --env release-signing \
    --repo nvprotas/notifilter
```

PowerShell:

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes("notifilter-release.jks")
) | gh secret set ANDROID_SIGNING_KEYSTORE_BASE64 `
  --env release-signing `
  --repo nvprotas/notifilter
```

Затем выполните команды по одной. `gh` запросит значение через стандартный
ввод, поэтому оно не окажется аргументом в истории shell:

```bash
gh secret set ANDROID_SIGNING_STORE_PASSWORD --env release-signing --repo nvprotas/notifilter
gh secret set ANDROID_SIGNING_KEY_ALIAS --env release-signing --repo nvprotas/notifilter
gh secret set ANDROID_SIGNING_KEY_PASSWORD --env release-signing --repo nvprotas/notifilter
```

Для alias введите `notifilter`, если использовали приведённую выше команду. У
JKS key password может совпадать со store password, но workflow нужны оба
секрета.

Публичный SHA-256 сертификата сохраните как environment variable:

```bash
gh variable set ANDROID_SIGNING_CERT_SHA256 \
  --env release-signing \
  --repo nvprotas/notifilter
```

При запросе вставьте SHA-256 из `keytool -list`. Environment variable защищает
от случайной загрузки другого keystore. Офлайн-копия fingerprint остаётся
независимым источником доверия.

Проверьте наличие всех имён, не раскрывая значений:

```bash
gh secret list --env release-signing --repo nvprotas/notifilter
gh variable list --env release-signing --repo nvprotas/notifilter
gh api repos/nvprotas/notifilter/environments/release-signing
```

Ожидаются четыре секрета и одна переменная. В environment должна быть разрешена
только ветка `master`. Если есть второй доверенный сопровождающий, дополнительно
назначьте его required reviewer в настройках Environment; не включайте
`prevent self-review`, пока такого человека нет.

## 4. Запустите подписанный release

Перед релизом увеличьте `versionCode` и обновите `versionName` в
`app/build.gradle.kts`. Узнайте ожидаемый commit и запустите workflow:

```bash
gh api repos/nvprotas/notifilter/commits/master --jq .sha
gh workflow run release.yml \
  --ref master \
  -f sign_release=true \
  --repo nvprotas/notifilter
gh run list \
  --workflow release.yml \
  --repo nvprotas/notifilter \
  --limit 1 \
  --json databaseId,headBranch,headSha,status,conclusion,url
```

Убедитесь, что `headBranch` равен `master`, а `headSha` совпадает с commit,
который вы собирались выпустить. Затем дождитесь результата и скачайте только
подписанный artifact:

```bash
gh run watch RUN_ID --repo nvprotas/notifilter --exit-status
gh run download RUN_ID \
  --repo nvprotas/notifilter \
  --name notifilter-release-signed
```

Artifact содержит:

- `notifilter-release.apk`;
- `notifilter-release.aab`;
- `SHA256SUMS`;
- отчёты о сертификатах и проверке подписей.

## 5. Проверьте скачанный release перед публикацией

На доверенной машине в каталоге artifact:

```bash
sha256sum --check SHA256SUMS
apksigner verify --verbose --print-certs notifilter-release.apk
keytool -printcert -jarfile notifilter-release.aab
jarsigner -verify -verbose -certs notifilter-release.aab
```

Сверьте SHA-256 сертификатов APK и AAB с сохранённым офлайн fingerprint. Только
после этого загружайте файлы на сайт или в магазины. `SHA256SUMS` обнаруживает
повреждение скачанного artifact, но не заменяет проверку сертификата.

Для проверки pipeline без ключа можно запустить workflow с
`-f sign_release=false`: GitHub выполнит тесты и release-сборку, а защищённую job
подписи пропустит.
