# JSound-OpenAL

把 `javax.sound.sampled`（Java Sound）音频桥接到 LWJGL OpenAL 的 Minecraft 双端模组，
让依赖 Java Sound 的音乐/语音模组在**安卓启动器（PojavLauncher / Zalith）**上正常播放与录音。

## 特性

- **SPI 反射注册**：Fabric/Forge 类加载器不扫 `META-INF/services`，通过反射把 Provider 注入 `AudioSystem`
- **独立 OpenAL 上下文**：单 worker 线程持有，不与游戏音效引擎抢资源；失败安全降级
- **双播放路径**：`Clip`（整段上传）+ `SourceDataLine`（流式：48 buffer 轮转 + 背压队列）
- **驱动怪癖修复**：安卓 OpenAL 的 `AL_BUFFERS_PROCESSED` 恒为 0 → 按 `inflight − driverQueued` FIFO 回收 + 8 buffer 预缓冲（消除开头沙沙声与断流死锁）
- **格式转换层**：8-bit（升位）/16-bit 大端/unsigned → signed 16-bit LE（`PCMConvert`/`PCMEncode`），老模组小 WAV、AU/AIFF 直接可用
- **语音输入**：`TargetDataLine`（ALC11 捕获线程 + 阻塞 read，需启动器麦克风权限）
- **音量控制**：MASTER_GAIN/MUTE，dB→线性，容错 `-Infinity`
- **日志门控**：`-Djsound.openal.debug=true`；`OPEN` 行与错误路径常开

## 支持

- Minecraft 1.20.1（Forge 47+）/ Fabric（loader ≥0.14，MC 任意版本）
- 格式：8/16-bit PCM，signed/unsigned，大/小端，单/立体声，采样率不限
- 不支持：MIDI（`javax.sound.midi`）、录音以外的 target line、裸编码流

## 构建（手机 Termux/DSH 环境实测）

需要 JDK 17+ 与参考包 `参考/lwjgl-glfw-classes.jar`（LWJGL OpenAL 类）：

```sh
find src/main/java build/_stubs -name "*.java" > build/_sources.txt
javac -encoding UTF-8 -cp "参考/lwjgl-glfw-classes.jar" -d build/_out @build/_sources.txt
rm -rf build/_out/net          # 剔除 Fabric/Forge 编译桩
cp -r src/main/resources/. build/_out/
jar --create --file jsound-openal.jar -C build/_out .
```

`build/_stubs/` 里的 Fabric/Forge API 桩仅编译期使用，运行时由加载器提供。

## 部署

`jsound-openal.jar` 丢进对应版本的 `mods/` 目录即可。详细分析文档见 [分析总结.md](分析总结.md)。

## 作者

**Rain_Clay** · License: MIT
