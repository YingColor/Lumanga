# 参与贡献

欢迎提 Issue 和 Pull Request。

## 提问题请附上

- 手机型号、安卓版本
- 漫画包大致体积、条目数量、图片格式
- 包内文件名是中文 / 日文 / 英文，是否套了多层文件夹
- 出现问题时的提示文字

**请不要上传有版权的漫画包。** 一个能复现问题的小样例包（自己生成的几张图片即可）就足够。

## 开发

- JDK 17+，Android Studio 或命令行 Android SDK（Platform 36 / Build Tools 36.0.0）
- `./gradlew assembleDebug`
- 代码风格：Kotlin 官方风格；界面文案统一放在 `UiStrings.kt`
- 提交前请确认没有把 keystore、APK 或个人路径写进仓库

## 设计约定

界面遵循 Material 3 Expressive 的紫色预设：大圆角、胶囊按钮、有弹性的按压反馈。新增组件请复用 `ui/Components.kt` 里的 `PillButton` / `SegmentedChoice`，保持视觉一致。

## 发布前检查（每次上传都要过一遍）

源码是公开的，提交前请确认下面四类内容没有混进去：

1. **不写内部过程**：注释和文档只写「代码为什么这样做」，不写需求讨论、bug 报告原话、评审意见、谁反馈了什么问题、试过又放弃的方案、待办清单。
2. **不写个人环境**：真实姓名、本机绝对路径（`D:/...`、`C:\Users\...`）、内网地址、API Key、keystore、测试用的手机序列号。
3. **不内置第三方服务**：翻译、抓取、翻译接口之类的地址一律留空，由使用者自己填；不要把别人的公开接口、测试 key 或私有部署当成默认值打包进去。
4. **许可证与素材对得上**：新增依赖或模型要在 `THIRD_PARTY_NOTICES.md` 写明版本、许可证、来源；不要打包别人的漫画、插画、字体、图标、安装包，也不要整段照抄其他项目的源代码或注释。

自查命令（可直接粘贴，最后一条应为空）：

```bash
# 1. 绝对路径 / 个人信息
git grep -nE "D:/|C:\\\\|/Users/|参考软件|_diag" -- '*.kt' '*.md' '*.kts' '*.xml' ':!CONTRIBUTING.md'
# 2. 内部过程词
git grep -nE "\bTODO\b|\bFIXME\b|\bXXX\b|\bHACK\b|用户反馈|用户要求|上一轮" -- '*.kt' '*.md' '*.kts' '*.xml' ':!CONTRIBUTING.md'
# 3. 第三方服务地址（只应剩下文档里提到的开源项目主页）
git grep -nE "https?://" -- '*.kt' | grep -viE "android\.com|apache\.org|github\.com|huggingface\.co|tensorflow\.org|w3\.org|slf4j\.org|tukaani\.org|google\.com|kotlinlang\.org"
# 4. 不该入库的文件
git ls-files | grep -iE "\.(apk|aab|jks|keystore|cbz|zip|rar|7z|dex)$"
```

前两条应当没有输出，第三条只应出现说明文字里的项目主页，第四条应为空。


