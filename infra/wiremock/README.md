# WireMock gRPC & REST モック環境

本ディレクトリは、Todo サービスから呼び出す外部 gRPC サービス（**KeyManagementService / KMS 暗号化サービス**）および REST API をモックするための設定です。

## モック対象サービス
* **KeyManagementService (`key_management_service.proto`)**:
  * Todo の機密データ（タスク内容やメモなど）を暗号化・復号化するために外部 KMS を呼び出す想定。
  * `rpc Encrypt(EncryptRequest) returns (EncryptResponse)`
  * `rpc Decrypt(DecryptRequest) returns (DecryptResponse)`

## ディレクトリ構成
```text
wiremock/
├── grpc/
│   └── key_management_service.proto    # KMS の Protocol Buffers 定義
├── mappings/
│   ├── grpc_kms_encrypt_ok.json        # Encrypt: 正常系 (200 OK)
│   ├── grpc_kms_decrypt_ok.json        # Decrypt: 正常系 (200 OK)
│   └── grpc_kms_key_not_found.json     # Encrypt: 不正なキー指定時のエラー (NOT_FOUND)
└── __files/
    └── grpc/
        ├── kms_encrypt_response.json   # 暗号化レスポンス (Base64 データ)
        └── kms_decrypt_response.json   # 復号レスポンス (Base64 データ)
```

## 起動方法
`docker-compose.yml` で WireMock コンテナを起動します。

```bash
docker compose up wiremock
```

ポート `8080` で HTTP/1.1 (REST) および HTTP/2 (gRPC) を同時に受け付けます。

## 動作確認 (grpcurl)
```bash
# 1. 暗号化 (Encrypt) の呼び出し
grpcurl -plaintext -import-path wiremock/grpc -proto key_management_service.proto \
  -d '{"key_name": "projects/todo/keys/task-key", "plaintext": "Vml0YWwgU2VjcmV0IFRvZG8gVGV4dA=="}' \
  localhost:8080 com.example.crypto.v1.KeyManagementService/Encrypt

# 2. 復号 (Decrypt) の呼び出し
grpcurl -plaintext -import-path wiremock/grpc -proto key_management_service.proto \
  -d '{"key_name": "projects/todo/keys/task-key", "ciphertext": "RU5DUllQVEVEX1RPRE9fREFUQQ=="}' \
  localhost:8080 com.example.crypto.v1.KeyManagementService/Decrypt

# 3. エラー系: 存在しないキー (NOT_FOUND)
grpcurl -plaintext -import-path wiremock/grpc -proto key_management_service.proto \
  -d '{"key_name": "projects/todo/keys/invalid-key", "plaintext": "..."}' \
  localhost:8080 com.example.crypto.v1.KeyManagementService/Encrypt
```
