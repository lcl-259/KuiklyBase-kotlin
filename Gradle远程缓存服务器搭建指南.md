# Gradle Remote Cache 服务器搭建指南

# 目录
1. [Docker 启动缓存服务器](#docker-启动缓存服务器)
2. [常见报错及解决方法](#常见报错及解决方法)
3. [客户端配置](#客户端配置)
4. [验证缓存是否工作](#验证缓存是否工作)
5. [注意事项](#注意事项)

---

# Docker 启动缓存服务器

## 1. 拉取镜像

```bash
docker pull gradle/build-cache-node:latest
```

## 2. 启动容器

```bash
docker run -d \
  -p 5071:5071 \
  -v gradle-cache-data:/data \
  --name gradle-cache \
  --platform linux/amd64 \
  gradle/build-cache-node:latest
```

**参数说明：**
- `-d`：后台运行
- `-p 5071:5071`：端口映射（主机端口:容器端口）
- `-v gradle-cache-data:/data`：数据卷持久化
- `--name gradle-cache`：容器名称
- `--platform linux/amd64`：指定平台（Mac M1/M2 需要）

## 3. 验证容器运行

```bash
# 查看容器状态
docker ps | grep gradle-cache

# 查看容器日志
docker logs gradle-cache
```

**正常输出示例：**
```
Starting Develocity build cache node (21.2) ...
UI access is protected by generated username and password: user532 bu7ppovqaaj643qc3xcdzbly2a
Build cache node started (port: 5071).
```

---

# 常见报错及解决方法

## 报错 1：访问缓存时返回 403 Forbidden

## 错误现象

在 Gradle 构建日志中看到：
```
Could not load entry e6f020ab9cf12e4b600636d66c2f050e from remote build cache:
Loading entry from 'http://localhost:5071/cache/e6f020ab9cf12e4b600636d66c2f050e'
response status 403: Forbidden
The remote build cache was disabled during the build due to errors.
```

在 Docker 日志中看到：
```
WARNING: This build cache is unusable as a build cache due to its access control settings -
anonymous access is disabled and no users are defined.
```

## 原因分析

默认情况下，Develocity Build Cache Node **禁用了匿名访问**，只有配置了用户或启用匿名访问后才能使用。

## 解决方法

**通过 Web UI 配置（推荐）：**

1. 从 Docker 日志中获取临时用户名和密码：
   ```bash
   docker logs gradle-cache | grep "username and password"
   ```

   输出示例：
   ```
   UI access is protected by generated username and password: user532 bu7ppovqaaj643qc3xcdzbly2a
   ```

2. 在浏览器访问：`http://localhost:5071`

3. 使用上面的用户名密码登录：
   - 用户名：`user532`
   - 密码：`bu7ppovqaaj643qc3xcdzbly2a`

4. 点击 **"Build cache"** → **"Settings"**

5. 找到 **"Access control"** 部分

6. 将 **"Anonymous access level"** 改为 **"Read and write"**

7. 点击 **"Save"** 保存

8. 重启容器使配置生效：
   ```bash
   docker restart gradle-cache
   ```

9. 验证配置成功：
   ```bash
   docker logs gradle-cache | tail -5
   ```

   应该看到：
   ```
   WARNING: Write access to the build cache is unrestricted
   Build cache node started (port: 5071).
   ```

---

## 报错 2：平台不匹配警告

## 错误现象

```
WARNING: The requested image's platform (linux/amd64) does not match
the detected host platform (linux/arm64/v8) and no specific platform was requested
```

## 原因分析

你在 Mac M1/M2（ARM 架构）上运行 AMD64 架构的镜像。

## 解决方法

在启动容器时添加 `--platform linux/amd64` 参数（已在上面的启动命令中包含）。

**影响：**
- 性能略有下降（通过 Rosetta 转译）
- 功能完全正常

---

## 报错 3：密码格式错误

## 错误现象

如果尝试通过配置文件设置明文密码：
```
ERROR: Config file mounted to '/data/conf/config.yaml' inside a container is invalid:
  - Property 'cache.accessControl.users.lcl.password' is invalid:
    does not match the regex pattern ^(?:\s*[A-Za-z0-9+/]){43}(?:\s*=):...
```

## 原因分析

Develocity Build Cache Node **不接受明文密码**，密码必须是特殊的加密格式。

## 解决方法

**不要尝试手动配置用户密码**，而是使用以下两种方式之一：

1. **启用匿名访问（推荐，用于本地测试）**
   - 通过 Web UI 配置（见报错 1 的解决方法）

2. **通过 Web UI 添加用户**
   - 登录管理界面
   - 在 "Users" 部分点击 "Add user"
   - 系统会自动生成加密密码

---

# 客户端配置

## 项目中的 settings.gradle 配置

在项目根目录的 `settings.gradle` 文件中添加：

```groovy
buildCache {
    local {
        enabled = true
    }

    remote(HttpBuildCache) {
        // 远程缓存服务器地址（注意 /cache/ 后缀）
        url = 'http://localhost:5071/cache/'

        // 是否允许推送到远程缓存
        push = true  // 本地测试设为 true，生产环境改为 System.getenv("CI") != null

        // 启用远程缓存
        enabled = true

        // 允许 HTTP 协议（本地测试用，生产环境应使用 HTTPS）
        allowInsecureProtocol = true
    }
}
```

## 端点说明

| 地址 | 用途 | 访问方式 |
|------|------|---------|
| `http://localhost:5071/` | Web UI 管理界面 | 浏览器 |
| `http://localhost:5071/cache/` | Build Cache API | Gradle |

**重要：** Gradle 配置中必须使用 `/cache/` 后缀，否则无法连接！

---

# 验证缓存是否工作

## 方法 1：查看任务输出（最直观）

## 步骤 1：第一次构建（推送缓存）

```bash
./gradlew build --build-cache
```

观察输出，大部分任务正常执行。

## 步骤 2：完全清理

```bash
# 清理项目构建输出
./gradlew clean

# 删除本地缓存（关键步骤！）
rm -rf ~/.gradle/caches/build-cache-1
```

## 步骤 3：第二次构建（从远程拉取）

```bash
./gradlew build --build-cache | grep FROM-CACHE
```

**如果看到类似输出，说明缓存工作正常：**
```
> Task :kotlin-util-io:compileKotlin FROM-CACHE
> Task :kotlin-stdlib:compileKotlin FROM-CACHE
> Task :core:builtins:serialize FROM-CACHE
```

**关键判断：**
- 本地缓存已删除 ✓
- 任务显示 `FROM-CACHE` ✓
- → **确认是从远程缓存加载！**

---

## 方法 2：统计缓存命中率

保存构建日志并统计：

```bash
# 运行构建并保存日志
./gradlew build --build-cache 2>&1 | tee build.log

# 统计从缓存加载的任务数量
echo "FROM-CACHE 任务数: $(grep -c 'FROM-CACHE' build.log)"

# 统计 Kotlin 编译任务缓存
echo "Kotlin 编译缓存: $(grep -c 'compileKotlin FROM-CACHE' build.log)"

# 查看总任务数
echo "总任务数: $(grep -c '> Task :' build.log)"
```

**示例输出：**
```
FROM-CACHE 任务数: 1434
Kotlin 编译缓存: 366
总任务数: 18783
```

---

## 方法 3：查看服务器统计

## 通过 Web UI 查看

访问 `http://localhost:5071`，可以看到：
- **Cache entries**：缓存条目数量
- **Cache size**：缓存占用磁盘空间
- **Requests**：总请求数
- **Hits**：缓存命中次数

## 通过命令行查看

```bash
# 查看缓存文件数量
docker exec gradle-cache sh -c "find /data/system/cache/artifacts-v2 -name '*.artifact' | wc -l"

# 查看缓存大小
docker exec gradle-cache du -sh /data/system/cache
```

**示例输出：**
```
24              # 缓存文件数量
4.8M            # 缓存总大小
```

---

## 方法 4：验证完整构建流程

适用于 Kotlin/Native 项目的完整验证：

```bash
# 第一次完整构建
time bash ./scripts/kuikly-base/publish-local.sh

# 记录时间，例如：20分11秒

# 完全清理
./gradlew clean
rm -rf ~/.gradle/caches/build-cache-1
rm -rf ./kotlin-native/dist
rm -rf ./build/repo

# 第二次完整构建
time bash ./scripts/kuikly-base/publish-local.sh 2>&1 | tee build.log

# 记录时间，例如：1分13秒
```

**性能提升示例：**
```
第一次构建：20分11秒
第二次构建： 1分13秒
━━━━━━━━━━━━━━━━━━
加速比：16.5 倍 🚀
```

---

# 注意事项

## 1. 本地缓存 vs 远程缓存

## Gradle 缓存查找顺序

```
1. 构建输出目录（如果文件还在）
   ↓ 未找到
2. 本地缓存 (~/.gradle/caches/build-cache-1)
   ↓ 未找到
3. 远程缓存 (http://localhost:5071/cache/)
   ↓ 未找到
4. 重新执行任务
```

## 验证远程缓存时必须删除本地缓存

```bash
# ⚠️ 错误的验证方式
./gradlew clean
./gradlew build --build-cache  # ← 还是从本地缓存加载

# ✅ 正确的验证方式
./gradlew clean
rm -rf ~/.gradle/caches/build-cache-1  # ← 删除本地缓存
./gradlew build --build-cache  # ← 确保从远程缓存加载
```

---

## 2. 任务状态说明

| 状态 | 含义 | 示例 |
|------|------|------|
| `FROM-CACHE` | 从缓存加载 | `> Task :app:compileKotlin FROM-CACHE` |
| `UP-TO-DATE` | 输出已存在，跳过 | `> Task :app:processResources UP-TO-DATE` |
| `SKIPPED` | 条件不满足，跳过 | `> Task :app:test SKIPPED` |
| `NO-SOURCE` | 无源代码，跳过 | `> Task :app:compileJava NO-SOURCE` |
| 无标记 | 实际执行 | `> Task :app:build` |

**重要区别：**
- `FROM-CACHE` = 使用了构建缓存（可能是本地或远程）
- `UP-TO-DATE` = 没有使用缓存，只是输出文件还在

---

## 3. 哪些任务会被缓存？

## ✅ 可缓存的任务（最重要）

- **Kotlin 编译**：`compileKotlin`、`compileJava`
- **单元测试**：`test`
- **代码生成**：各种 `generate*` 任务
- **资源处理**：`processResources`

## ❌ 不可缓存的任务

- **配置任务**：大部分配置阶段的任务
- **Maven 任务**：`mvn install`、`mvn deploy`
- **自定义任务**：没有标注 `@CacheableTask` 的任务
- **包含时间戳的任务**：输出包含当前时间的任务

---

## 4. includeBuild 项目的缓存

如果项目使用了 `includeBuild`（复合构建），子项目有自己的 `settings.gradle`：

```
主项目/settings.gradle          ← 你配置的
子项目/repo/gradle-settings-conventions/settings.gradle.kts  ← 也有自己的配置
```

**解决方法：**
所有子项目都使用默认的本地缓存路径（`~/.gradle/caches/build-cache-1`），这样主项目和子项目会共享同一个本地缓存目录。

---

## 5. push 策略

```groovy
// 本地开发：只拉取，不推送
push = false

// 本地测试：推送和拉取都启用
push = true

// CI 环境：只有 CI 推送
push = System.getenv("CI") != null
```

**推荐策略：**
- **本地开发机**：`push = false`（只从远程拉取，不推送）
- **CI 服务器**：`push = true`（推送构建结果供其他人使用）
- **本地测试**：`push = true`（验证功能时使用）

---

## 6. 缓存大小管理

## 查看缓存大小

```bash
# 本地缓存
du -sh ~/.gradle/caches/build-cache-1

# 远程缓存
docker exec gradle-cache du -sh /data/system/cache
```

## 清理本地缓存

```bash
# 删除本地缓存
rm -rf ~/.gradle/caches/build-cache-1

# Gradle 会在下次构建时自动重新创建
```

## 配置缓存大小限制

在 Web UI 中（`http://localhost:5071` → Settings）可以配置：
- **Target cache size**：目标缓存大小（默认 10 GB）
- **Max entry age**：缓存条目最大保留时间（默认：无限制）

---

## 7. 生产环境部署

本文档适用于**本地测试**，生产环境需要额外配置：

## 必须修改的配置

1. **使用 HTTPS**
   ```groovy
   url = 'https://cache.example.com/cache/'
   allowInsecureProtocol = false  // 禁用 HTTP
   ```

2. **配置认证**
   ```groovy
   credentials {
       username = System.getenv("CACHE_USERNAME")
       password = System.getenv("CACHE_PASSWORD")
   }
   ```

3. **限制 Push 权限**
   ```groovy
   push = System.getenv("CI") != null  // 只有 CI 能推送
   ```

4. **持久化数据**
   ```bash
   # 使用指定目录而不是 Docker volume
   docker run -d \
     -p 5071:5071 \
     -v /path/to/cache-data:/data \
     --name gradle-cache \
     gradle/build-cache-node:latest
   ```

---

# 快速参考

## 常用命令

```bash
# 启动服务器
docker start gradle-cache

# 停止服务器
docker stop gradle-cache

# 查看日志
docker logs gradle-cache

# 重启服务器
docker restart gradle-cache

# 删除容器（保留数据）
docker rm gradle-cache

# 删除容器和数据
docker rm gradle-cache && docker volume rm gradle-cache-data

# 查看缓存大小
docker exec gradle-cache du -sh /data/system/cache

# 查看缓存文件数量
docker exec gradle-cache find /data/system/cache/artifacts-v2 -name '*.artifact' | wc -l
```

## 验证缓存的标准流程

```bash
# 1. 清理
./gradlew clean
rm -rf ~/.gradle/caches/build-cache-1

# 2. 构建
./gradlew build --build-cache

# 3. 检查
./gradlew build --build-cache | grep FROM-CACHE
```

---

# 故障排除检查清单

- [ ] Docker 容器正在运行：`docker ps | grep gradle-cache`
- [ ] 容器日志无报错：`docker logs gradle-cache`
- [ ] 已启用匿名访问：日志中有 "Write access to the build cache is unrestricted"
- [ ] settings.gradle 配置正确：`url = 'http://localhost:5071/cache/'`
- [ ] allowInsecureProtocol 已设置：`allowInsecureProtocol = true`
- [ ] 已删除本地缓存：`ls ~/.gradle/caches/build-cache-1` 返回不存在
- [ ] Gradle 版本兼容：Gradle 6.0+

---

**文档版本：** 2026-01-06
**适用范围：** Gradle Build Cache Node 21.2, Docker
**测试环境：** macOS (ARM64), Gradle 8.8, Kotlin/Native 项目
