# ============================================================================
# Multi-stage Dockerfile for AI Code Review Assistant
# ============================================================================
# Stage 1: Build the application using Maven
# Stage 2: Run the application with a lightweight JRE image
# ============================================================================

# ----------- Stage 1: Build -----------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# 先复制 pom.xml，利用 Docker 缓存层加速依赖下载
# 只要 pom.xml 不变，依赖层就不会重新下载
COPY pom.xml .

# 下载依赖（仅当 pom.xml 变化时才重新执行）
RUN mvn dependency:go-offline -B

# 再复制源代码
COPY src ./src

# 构建项目，跳过测试（测试在 CI 中单独跑）
RUN mvn package -DskipTests -B

# ----------- Stage 2: Run -----------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 创建非 root 用户运行应用（安全最佳实践）
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# 从 builder 阶段复制构建产物
COPY --from=builder /app/target/ai-code-review-assistant-0.0.1-SNAPSHOT.jar app.jar

# 修改文件所有者
RUN chown -R appuser:appgroup /app

# 切换到非 root 用户
USER appuser

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# 启动应用
ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
