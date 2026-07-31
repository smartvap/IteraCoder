package com.agenthub.ai.base.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 项目管理服务 — ZIP 存储、解压、Docker 部署
 * @since 2026-07-29 — ZIP 存储、解压、Docker 部署
 */
@Slf4j
@Service
public class ProjectService {

    @Value("${agenthub.project.dir:Project}")
    private String projectDir;

    /** 保存日志到 logs/ 目录 */
    public String saveLog(String content) {
        try {
            Path logDir = Paths.get("logs");
            Files.createDirectories(logDir);
            String filename = "chat-log-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt";
            Path logFile = logDir.resolve(filename);
            Files.writeString(logFile, content);
            log.info("日志已保存: {}", logFile.toAbsolutePath());
            return logFile.toAbsolutePath().toString();
        } catch (Exception e) {
            log.error("保存日志失败", e);
            throw new RuntimeException("保存日志失败: " + e.getMessage());
        }
    }
    public String saveProject(MultipartFile file, String projectName) {
        try {
            Path baseDir = Paths.get(projectDir);
            Files.createDirectories(baseDir);
            Path targetDir = baseDir.resolve(projectName);
            // 清理旧目录
            if (Files.exists(targetDir)) {
                deleteRecursive(targetDir);
            }
            Files.createDirectories(targetDir);

            // 解压 ZIP
            try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    Path entryPath = targetDir.resolve(sanitizePath(entry.getName()));
                    Files.createDirectories(entryPath.getParent());
                    try (FileOutputStream fos = new FileOutputStream(entryPath.toFile())) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
            log.info("项目已保存: {}", targetDir.toAbsolutePath());
            return targetDir.toAbsolutePath().toString();
        } catch (Exception e) {
            log.error("保存项目失败: {}", projectName, e);
            throw new RuntimeException("保存项目失败: " + e.getMessage());
        }
    }

    /** 尝试 Docker 部署 */
    public Map<String, Object> deploy(String projectName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectName", projectName);

        Path projectPath = Paths.get(projectDir).resolve(projectName);
        if (!Files.exists(projectPath)) {
            result.put("success", false);
            result.put("message", "项目目录不存在: " + projectPath);
            return result;
        }

        boolean dockerAvailable = isDockerAvailable();
        result.put("dockerAvailable", dockerAvailable);

        if (!dockerAvailable) {
            result.put("success", false);
            result.put("message", "Docker 未安装或不可用。请手动安装 Docker 后运行：\n" +
                    "cd " + projectPath.toAbsolutePath() + "\n" +
                    "docker build -t " + projectName.toLowerCase() + " .\n" +
                    "docker run -d -p 8080:8080 " + projectName.toLowerCase());
            return result;
        }

        try {
            // 生成 Dockerfile
            boolean hasDockerfile = Files.exists(projectPath.resolve("Dockerfile"));
            if (!hasDockerfile) {
                generateDockerfile(projectPath);
                result.put("dockerfileGenerated", true);
            }

            // docker build
            String imageName = projectName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
            ProcessBuilder buildPb = new ProcessBuilder(
                    "docker", "build", "-t", imageName, "."
            );
            buildPb.directory(projectPath.toFile());
            Process buildProcess = buildPb.start();
            String buildOutput = readStream(buildProcess.getInputStream());
            String buildError = readStream(buildProcess.getErrorStream());
            int buildExit = buildProcess.waitFor();

            if (buildExit != 0) {
                result.put("success", false);
                result.put("message", "Docker 构建失败\n" + buildError);
                return result;
            }
            result.put("buildOutput", buildOutput);

            // docker run
            ProcessBuilder runPb = new ProcessBuilder(
                    "docker", "run", "-d", "-p", "8080:8080", "--name", imageName, imageName
            );
            Process runProcess = runPb.start();
            String runOutput = readStream(runProcess.getInputStream());
            int runExit = runProcess.waitFor();

            if (runExit == 0) {
                result.put("success", true);
                result.put("containerId", runOutput.trim());
                result.put("message", "Docker 部署成功！容器ID: " + runOutput.trim() + "\n访问 http://localhost:8080");
            } else {
                result.put("success", false);
                result.put("message", "Docker 运行失败: " + readStream(runProcess.getErrorStream()));
            }
        } catch (Exception e) {
            log.error("Docker 部署异常: {}", projectName, e);
            result.put("success", false);
            result.put("message", "部署异常: " + e.getMessage());
        }

        return result;
    }

    /** 检查 Docker 是否可用 */
    public boolean isDockerAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "--version");
            Process p = pb.start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 生成简单的 Dockerfile */
    private void generateDockerfile(Path projectPath) throws IOException {
        boolean isMaven = Files.exists(projectPath.resolve("pom.xml"));
        boolean isGradle = Files.exists(projectPath.resolve("build.gradle"));
        boolean isNode = Files.exists(projectPath.resolve("package.json"));
        boolean isPython = Files.exists(projectPath.resolve("requirements.txt")) || Files.list(projectPath).anyMatch(p -> p.toString().endsWith(".py"));

        StringBuilder df = new StringBuilder();
        if (isMaven || isGradle) {
            df.append("FROM openjdk:17-jdk-slim\nWORKDIR /app\nCOPY . .\n");
            if (isMaven) {
                df.append("RUN ./mvnw package -DskipTests 2>/dev/null || mvn package -DskipTests\n");
            } else {
                df.append("RUN ./gradlew build -x test 2>/dev/null || gradle build -x test\n");
            }
            df.append("EXPOSE 8080\nCMD [\"java\", \"-jar\", \"target/*.jar\"]\n");
        } else if (isNode) {
            df.append("FROM node:18-alpine\nWORKDIR /app\nCOPY package*.json ./\nRUN npm install\nCOPY . .\nEXPOSE 3000\nCMD [\"npm\", \"start\"]\n");
        } else if (isPython) {
            df.append("FROM python:3.11-slim\nWORKDIR /app\nCOPY requirements.txt . 2>/dev/null\nRUN pip install -r requirements.txt 2>/dev/null || true\nCOPY . .\nEXPOSE 8000\nCMD [\"python\", \"app.py\"]\n");
        } else {
            df.append("FROM ubuntu:latest\nWORKDIR /app\nCOPY . .\nCMD [\"echo\", \"No runtime detected, please configure manually\"]\n");
        }

        Files.writeString(projectPath.resolve("Dockerfile"), df.toString());
        log.info("已生成 Dockerfile: {}", projectPath.resolve("Dockerfile"));
    }

    private String readStream(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private String sanitizePath(String name) {
        // 防止 Zip Slip 攻击
        return name.replace("..", "").replace("\\", "/");
    }

    private void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var files = Files.list(path)) {
                files.forEach(p -> {
                    try { deleteRecursive(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
