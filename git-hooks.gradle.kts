// 注册一个 installGitHooks 任务，用于安装 pre-push 钩子
tasks.register("installGitHooks") {
    group = "help"
    description = "Install local git hooks to ensure code quality before push"
    
    doLast {
        val prePushSrc = File(rootProject.rootDir, "scripts/pre-push")
        val prePushDest = File(rootProject.rootDir, ".git/hooks/pre-push")
        
        if (prePushSrc.exists()) {
            // 复制文件
            prePushSrc.copyTo(prePushDest, overwrite = true)
            // 赋予执行权限 (在 Linux/Mac 上有效，Windows 不需要)
            prePushDest.setExecutable(true)
            println("Git pre-push hook installed successfully.")
        } else {
            println("Error: scripts/pre-push file not found.")
        }
    }
}

