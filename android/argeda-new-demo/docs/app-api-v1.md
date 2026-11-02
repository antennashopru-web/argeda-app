# API для мобильного приложения ARGEDA — v1

Задание для треда «Работа на сервере argeda.ru». Приложение «ARGEDA» (Android, пакет `ru.argeda.design`) уже нарисовано и собрано как демо. Чтобы оно стало боевым, ему нужны данные с сервера: каталог, цены и остатки, заказы, запись на ремонт, вход.

Порядок работы как обычно: сначала копия и показ, выкладка только по «выкладывай», бэкап базы перед выкладкой.

## Общие правила

- **Адрес:** `https://argeda.ru/api/app/v1/…`. В `robots.txt` `/api` уже закрыт.
- **Формат:** JSON в UTF-8, сжатие gzip. Даты в ISO 8601 с часовым поясом, цены — целые рубли.
- **Ошибки:** HTTP-код и тело `{"error": {"code": "out_of_stock", "message": "Текст для клиента"}}`.
- **CORS:** приложение открывает страницы с адреса `https://appassets.androidplatform.net`. Для `/api/app/*` нужны:
  - `Access-Control-Allow-Origin: https://appassets.androidplatform.net`;
  - `Access-Control-Allow-Headers: Authorization, Content-Type, X-Argeda-Test, Idempotency-Key`;
  - ответ на `OPTIONS`.
- **Вход:** заголовок `Authorization: Bearer <token>`. Каталог и запись на ремонт работают и без входа.
- **Тестовый режим.** С заголовком `X-Argeda-Test: 1` заказы и записи сохраняются с пометкой «тест». В CRM, Telegram и SMS они не уходят, в админке видны отдельно. По нему облачный тред проверяет приложение, не создавая настоящих заказов.
- **Цены.** Сервер — единственный источник цен. При оформлении заказа он пересчитывает всё сам, цены из приложения только для показа.
- **Источник.** Заказы из приложения идут по тому же пути, что заказы с сайта: CRM ARGEDA и уведомления. Поле `source = "app"`.
- **Персональные данные (152-ФЗ).** Данные клиентов хранятся только на сервере в России. Вместе с заказом сохраняются версия текста согласия и время, когда клиент его дал.
- **Не отдавать** в API пароли, внутренние закупочные цены, данные других клиентов.

## 1. Настройки приложения

`GET /config`

```json
{
  "min_version": 1, "latest_version": 1, "apk_url": "https://argeda.ru/app/argeda.apk",
  "pickup": {"address": "Пушкино, ул. Лермонтова, 2", "hours": "10:00–22:00", "lat": 56.0, "lon": 37.8},
  "phone": "+79939000077",
  "consent": {"version": "2026-10-01", "url": "https://argeda.ru/privacy"},
  "features": {"sms_login": true, "online_payment": false, "courier": true}
}
```

- `min_version`: если версия приложения ниже, оно попросит обновиться.
- `pickup` и `phone`: настоящие данные. Сейчас в приложении часы «до 22:00» — сверьте.
- `lat`/`lon` в примере условные. Нужны настоящие координаты пункта выдачи.

## 2. Каталог

`GET /catalog/categories` — дерево разделов:

```json
[{"id": 12, "slug": "akkumulyatory", "name": "Аккумуляторы", "parent_id": null, "count": 1840}]
```

`GET /catalog/home` — блоки главного экрана:

```json
{"banners": [{"title": "iPhone 17 Pro", "subtitle": "256 ГБ · под заказ 2 дня", "product_id": 501, "image": "…"}],
 "quick": [12, 15, 31, 40], "in_stock_today": [ProductShort, …]}
```

`GET /catalog/products?category=12&q=аккумулятор&page=1&limit=40&sort=popular|price_asc|price_desc|new` возвращает `{"items": [ProductShort], "total": 1840, "page": 1}`.

`GET /catalog/products/{id}` возвращает `ProductFull`.

`GET /catalog/sync?since=2026-10-23T12:00:00+03:00` — изменения с указанного времени, чтобы каталог работал без интернета:

```json
{"server_time": "…", "changed": [ProductShort], "deleted": [101, 102], "full": false}
```

Без `since` или если с прошлого раза прошло больше 7 дней, отдать весь каталог (`"full": true`). Желательно кусками: `page` и `limit` до 2000.

**ProductShort**

```json
{"id": 501, "name": "Аккумулятор для Huawei P50 Pro (HB536479ECW)", "price": 610, "old_price": null,
 "image": "https://argeda.ru/products/min/400/6234f9d44085de00.webp.webp",
 "in_stock": true, "availability": "today", "preorder_days": null,
 "category_id": 12, "seller": {"id": 1, "name": "ARGEDA"}, "install_service_id": 7, "updated_at": "…"}
```

- `availability`: `today` (есть в пункте) или `preorder` (под заказ; срок в `preorder_days`).
- `install_service_id`: какая услуга мастера подходит к запчасти. Это кнопка «Установить у мастера» в карточке товара. Если подходящей услуги нет — `null`.

**ProductFull** — то же, плюс `images` (до 8 штук, 800 px), `description` (простой текст), `attributes` (`[{"name": "Ёмкость", "value": "4360 мА·ч"}]`) и `fits` (список моделей, для которых подходит).

## 3. Ремонт (данные uslugi.argeda.ru)

`GET /repair/services?model=Samsung%20Galaxy%20A01` — цены для модели. Если модель неизвестна, отдать общие цены «от»:

```json
{"model": "Samsung Galaxy A01", "model_found": true,
 "services": [{"id": 7, "problem": "battery", "name": "Замена аккумулятора", "price_from": 1950, "duration": "30–60 минут"}]}
```

Значения `problem`: `screen`, `battery`, `port`, `water`, `camera`, `other`.

Приложение присылает модель в виде «производитель + модель» из настроек Android, например «Samsung SM-A015F». Сервер сам сопоставляет её с моделью на сайте. Если не нашёл — `model_found: false`.

`GET /repair/slots?date=2026-10-24` возвращает свободное время: `{"date": "2026-10-24", "slots": ["10:00", "10:30"]}`.

`POST /repair/bookings`. Ответ — объект записи с номером `R-…`:

```json
{"model": "Samsung Galaxy A01", "problem": "battery", "service_id": 7, "date": "2026-10-24", "time": "12:00",
 "phone": "+79161234567", "name": "", "comment": "", "consent_version": "2026-10-01"}
```

`GET /repair/bookings` (нужен вход) — мои записи.

## 4. Заказы

`POST /orders/quote` — пересчёт корзины перед оформлением:

```json
{"lines": [{"product_id": 501, "qty": 2}], "services": [{"service_id": 7, "for_product_id": 501}],
 "delivery": {"type": "pickup"}}
```

Ответ: актуальные цены, наличие, итог и предупреждения, например:

```json
"warnings": [{"product_id": 501, "code": "price_changed", "old": 610, "new": 590}]
```

`POST /orders` — тело как у `quote`, плюс:
- `phone`, `name`, `comment`, `consent_version`;
- `payment: "on_receipt"`;
- `delivery: {"type": "courier", "address": "…"}` — для курьера;
- заголовок `Idempotency-Key`, чтобы повтор запроса не создал второй заказ.

Ответ — `Order`.

`GET /orders` (нужен вход) — мои заказы, новые сверху. `GET /orders/{id}` — один заказ.

**Order**

```json
{"id": 10482, "number": "A-10482", "status": "ready", "status_text": "Готов к выдаче",
 "created_at": "…", "lines": […], "services": […], "totals": {"goods": 1289, "services": 1950, "delivery": 0, "total": 3239},
 "delivery": {"type": "pickup"}, "pickup_code": "A-10482-7F3K",
 "steps": [{"title": "Заказ принят", "at": "…", "done": true}, {"title": "Готов к выдаче", "at": null, "done": false}]}
```

- `status`: `new`, `confirmed`, `in_work`, `ready`, `done` или `cancelled`.
- `pickup_code`: строка для QR-кода на кассе. Отдавать только когда `status = ready`. Её должно быть нельзя подобрать перебором.

## 5. Вход по номеру телефона

- `POST /auth/code` с телом `{"phone": "+79161234567"}` отправляет SMS с 4-значным кодом и отвечает `{"ok": true, "retry_after": 60}`.
  - Повторная отправка на номер — не чаще раза в минуту и не больше 5 раз в час.
  - Код живёт 5 минут, на ввод даётся 5 попыток.
  - С заголовком `X-Argeda-Test: 1` SMS не отправлять, код всегда `0000`, но только для номеров из тестового списка.
- `POST /auth/verify` с телом `{"phone": "…", "code": "1234"}` отвечает `{"token": "…", "user": {"id": 1, "phone": "…", "name": "…"}}`.
  - Токен живёт 90 дней.
  - Если клиент с таким номером уже есть на сайте, вернуть его аккаунт: заказы с сайта должны появиться в приложении.
- `GET /me` — данные клиента. `POST /auth/logout` — выход.

Если SMS-провайдера пока нет, сделайте вход так же, как он устроен на сайте сейчас, и напишите, как именно: под это подстроим приложение.

## 6. Уведомления (вторым этапом)

`POST /devices` с телом `{"push_token": "…", "provider": "rustore" | "fcm", "app_version": 1}`. Привязывается к клиенту, если он вошёл.

Сервер шлёт уведомления:
- когда заказ перешёл в `ready` — «Заказ A-10482 готов к выдаче»;
- в день записи на ремонт, утром — напоминание.

Ключи RuStore Push и FCM даст владелец, когда заведём проект в консоли RuStore.

## 7. Файлы для приложения на сайте

- `https://argeda.ru/app` — страница «Скачать приложение для Android» с кнопкой и QR-кодом.
- `https://argeda.ru/app/argeda.apk` — сам файл. Отдавать с типом `application/vnd.android.package-archive`.
- `https://argeda.ru/.well-known/assetlinks.json` — сделаем, когда будет постоянный ключ подписи: облачный тред пришлёт отпечаток.

## Что прислать в ответ

1. Базовый адрес и подтверждение, что тестовый режим `X-Argeda-Test` работает.
2. Примеры настоящих ответов `GET /config`, `GET /catalog/products?limit=2`, `GET /repair/services?model=Samsung Galaxy A01`.
3. Тестовый номер телефона для входа с кодом `0000`.
4. Чем отличается то, что сделано, от этого описания: приложение подстроим.
