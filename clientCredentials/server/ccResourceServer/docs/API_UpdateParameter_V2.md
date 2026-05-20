# API 介面文件：參數更新 (Update Parameter)

## 1. 基本資訊

**功能描述：** 提供更新參數設定或僅驗證參數有效性的功能。

### 1.1 HTTP Interface

#### 1.1.1 PUT/POST

| 項目 | 內容 |
|------|------|
| Method | POST |
| URI | `sEQI/Param/UpdateParameter` |
| Authentication | OAuth 2.0 (RFC 6749) Client Credentials Grant Flow<br>(使用者身份驗證的 Bearer Token) |
| Request headers | Authorization: 用於認證授權<br>其他請見以下章節 2.2 CustomRequest headers |
| Request Parameters | None |
| Request content type | 請求體的編碼格式 JSON<br>`application/json ; charset=utf-8` |

**Request body**

| 屬性名稱 | 資料型態 | 說明 |
|----------|----------|------|
| body | JSON object | Defined by API |

| 項目 | 內容 |
|------|------|
| Response status | 200：資料已處理並回傳<br>400：Request body 格式錯誤<br>401：未帶認證資訊或認證失敗<br>404：URI 錯誤<br>405：不支援的請求方法<br>5xx：伺服器錯誤 |
| Response content type | `application/json` |

**Response body**

```json
{
  "Check_Valid_Only": "Y",
  "Request": [
    {
      "Param_Type": "01",
      "Version": "100"
    }
  ]
}
```

### 1.2 共用標頭 | CustomRequest headers

**RESTful API Request Header**

| 屬性名稱 | 中文名稱 | 資料型態 | 必填 | 說明 |
|----------|----------|----------|------|------|
| Authorization | 使用者身份驗證的 Bearer Token | string | Y | 範例值：`Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9` |

---

## 2. 請求參數 (Request Parameters)

請求必須包含在 Request Body 中,並使用 JSON 格式。

### 2.1 Root 結構 (UpdateParameterApiIn)

| 參數名稱 | JSON 欄位名稱 | 型別 | 必填 | 長度限制 | 描述 / 備註 |
|----------|---------------|------|------|----------|-------------|
| checkValidOnly | Check_Valid_Only | String | 是 | 最大 2 碼 | 是否僅進行驗證而不實際更新。不可為空。 |
| request | Request | Array | 是 | - | 參數資訊列表,參考 ParamInfo 結構。 |

### 2.2 ParamInfo 物件結構

| 參數名稱 | JSON 欄位名稱 | 型別 | 必填 | 長度限制 | 描述 / 備註 |
|----------|---------------|------|------|----------|-------------|
| paramType | Param_Type | String | 是 | 最大 2 碼 | 參數類型。不可為空。 |
| version | Version | String | 是 | 最大 15 碼 | 參數版本號。不可為空。 |

---

## 3. 請求範例 (Request Payload)

```json
{
  "Check_Valid_Only": "Y",
  "Request": [
    {
      "Param_Type": "01",
      "Version": "100"
    }
  ]
}
```

---

## 4. 回應結果 (Response)

### 4.1 回應結構

| 欄位名稱 | 型別 | 描述 / 備註 |
|----------|------|-------------|
| code | Integer | 狀態碼。0 為成功,-1 為錯誤。 |
| message | String | 狀態描述。成功時為 `"successful"`。 |

### 4.2 狀態碼字典

| 狀態碼 (code) | 訊息 (message) | 說明 |
|---------------|----------------|------|
| 0 | successful | 請求處理成功。 |
| -1 | (空字串) | 請求發生錯誤。 |

---

## 5. 回應範例 (Response Payload)

```json
{
  "code": 0,
  "message": "successful"
}
```