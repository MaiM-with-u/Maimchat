#!/usr/bin/env bash

# 示例脚本：生成 Android 发布签名证书
# 请在本地复制后替换占位符，并勿将真实口令信息提交到仓库。

keytool -genkeypair -v \
 -keystore YOUR_KEYSTORE_NAME.jks \
 -alias YOUR_KEY_ALIAS \
 -keyalg RSA -keysize 2048 -validity 3650 \
 -storepass YOUR_STORE_PASSWORD \
 -keypass YOUR_KEY_PASSWORD \
 -dname "CN=Your Name, OU=Your Org Unit, O=Your Org, L=Your City, ST=Your State, C=Your Country"