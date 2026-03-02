# 概览
MediaBackup 是一款基于 Android 13+ 的工具，扫描手机中所有图片/视频，使用 SAF 选择 USB OTG 目标，然后将文件有条理地复制到 MediaBackup/Images 和 MediaBackup/Videos，原始媒体保持不动。

# 功能亮点
- 通过 MediaStore.VOLUME_EXTERNAL_PRIMARY 扫描全量的图片与视频。
- 选择并持久化 USB OTG 目录权限，复制前可先扫描并缓存媒体列表。
- 提供“极速模式”（保持唤醒、跳过热控、直接写最终文件）和“常规模式”（热控节流 + 临时文件）。
- 实时进度展示传输时间、预计剩余、速度与已传大小，且支持终止按钮。
- 已复制记录跳过相同名称+文件大小的文件，目标目录自动写 .nomedia 避免系统再次扫描。
- 可靠性优化：Fast 模式防止锁屏中断；Normal 模式根据热状态延迟，避免降频。

# 测试记录
- 使用 oppo find x8 ultra系列手机，基于 android 15 进行测试，备份 70G，33200 个文件进行拷贝，顺利完成备份
