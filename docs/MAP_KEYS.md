# 高德 Key / 百度 AK 配置教程

豆位不提供共享地图凭据。安装后，在右上角 **设置 → 地图服务** 选择百度或高德，填入自己申请的 **Android 平台** 凭据。暂未使用的另一家可以留空。

## 一、先确认包名和签名 SHA-1

从本仓库 Releases 下载的签名安装包统一使用：

```text
应用名称：豆位
包名：com.laozhang.mockgps
SHA-1：B4:42:7A:64:7C:8A:92:04:D6:00:72:AD:1B:98:8C:5F:18:EC:57:E7
```

**SHA-1 是申请地图鉴权所需的公开证书指纹。** 不需要、也不提供签名私钥或签名密码。

自行编译并使用自己的签名时，先运行以下命令，再使用输出中的 SHA1。命令会交互询问密码，避免把密码写入命令历史：

```shell
keytool -list -v -keystore /path/to/your-release.jks -alias your-alias
```

也可以直接检查自己构建的 APK：

```shell
apksigner verify --print-certs app-release.apk
```

`apksigner` 位于 Android SDK 的 `build-tools/<版本>/`。包名和签名必须与**手机实际安装的 APK**一致。

## 二、申请高德 Android Key

1. 注册并登录 [高德开放平台](https://lbs.amap.com/)，按平台要求完成账号认证。
2. 打开 [应用管理](https://console.amap.com/dev/key/app)，创建应用，例如命名“豆位”。
3. 为应用添加 Key，服务平台选择 **Android 平台**。
4. 在发布版安全码 / SHA-1 中填写实际 APK 的证书 SHA-1，在 PackageName 中填写 `com.laozhang.mockgps`。如果单独填写调试版安全码，也必须与调试 APK 的实际签名一致。
5. 根据控制台要求确认地图、定位、搜索和路径规划等所需服务权限，保存并复制生成的 **Key**。
6. 在豆位 **设置 → 地图服务 → 高德地图** 中填写 Android Key 并保存。

参考：[Android 地图 SDK](https://lbs.amap.com/api/android-sdk/summary)、[Android 定位 SDK](https://lbs.amap.com/api/android-location-sdk/locationsummary)。控制台项目名称可能随平台更新略有变化。

## 三、申请百度 Android AK

1. 注册并登录 [百度地图开放平台](https://lbsyun.baidu.com/)，按平台要求完成认证。
2. 打开 [应用管理](https://lbsyun.baidu.com/apiconsole/key#/home)，创建应用，应用类型选择 **Android SDK**。
3. 根据使用情况启用 Android 地图、定位、检索 / 路线规划等服务权限。
4. 在安全码字段按 **`SHA-1;包名`** 格式填写。使用本仓库的官方签名包时，完整内容如下，分号使用英文半角：

```text
B4:42:7A:64:7C:8A:92:04:D6:00:72:AD:1B:98:8C:5F:18:EC:57:E7;com.laozhang.mockgps
```

5. 提交后复制生成的 **AK**，在豆位 **设置 → 地图服务 → 百度地图** 中填写并保存。

## 四、保存和验证

- 首次配置保存后即可加载地图；修改已初始化的凭据时，界面提示“保存并重启”，请先停止当前模拟。
- 两家凭据分别保存，切换地图不会清空另一家的配置。
- 用所选地图搜索一个地点，再规划一条短路线，确认地图、搜索与路线服务均可用。
- 凭据只保存在手机本地，收藏备份不包含它们。换机后需要重新填写。

## 常见问题

| 现象 | 检查方法 |
| --- | --- |
| 地图空白、鉴权失败 | 确认申请的是 Android 凭据，包名和 SHA-1 与实际安装包一致，且网络可用 |
| 地图正常，搜索或规划失败 | 检查对应服务权限、账号状态、配额和具体错误；确认起终点有效 |
| 自己编译后原 Key 不能用 | 用自己签名证书的 SHA-1 更新平台安全码 |
| 更换 Key 后仍提示失败 | 停止模拟，按“保存并重启”重新初始化 SDK；新配置是否已生效以平台为准 |
| 百度找不到“不走高速”选项 | 本版百度 SDK 不提供这一策略，请切换高德使用 |

免费发布豆位不代表地图服务提供无限免费配额，请以高德和百度控制台显示的权限、配额和收费规则为准。
