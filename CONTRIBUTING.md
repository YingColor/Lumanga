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

