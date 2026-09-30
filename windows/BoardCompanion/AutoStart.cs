using System;
using System.IO;
using System.Reflection;
using Microsoft.Win32;

namespace BoardCompanion
{
    /// <summary>
    /// 开机自启（当前用户）。
    ///
    /// 写的是 **HKCU\Software\Microsoft\Windows\CurrentVersion\Run**：
    /// - 只影响当前登录用户；
    /// - **不需要管理员权限、不弹 UAC**；
    /// - 登录后以托盘后台方式启动（不显示 Console）。
    ///
    /// 默认值：**保持现状**（从不主动写入；只有用户在设置里显式打开才写）。
    /// </summary>
    public static class AutoStart
    {
        public const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
        public const string ValueName = "BoardCompanion";

        /// <summary>当前可执行文件路径（带引号以防空格路径）</summary>
        public static string ExecutablePath
        {
            get
            {
                var path = Assembly.GetEntryAssembly() != null
                    ? Assembly.GetEntryAssembly().Location
                    : Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "BoardCompanion.exe");
                return path ?? string.Empty;
            }
        }

        /// <summary>命令行写入注册表的形式： "C:\...\BoardCompanion.exe" --tray</summary>
        public static string CommandLine
        {
            get { return "\"" + ExecutablePath + "\" --tray"; }
        }

        public static bool IsEnabled(string runKeyPath = RunKeyPath)
        {
            try
            {
                using (var key = Registry.CurrentUser.OpenSubKey(runKeyPath, false))
                {
                    if (key == null) return false;
                    var value = key.GetValue(ValueName) as string;
                    return !string.IsNullOrEmpty(value);
                }
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>打开/关闭自启。返回是否成功（失败不抛）。</summary>
        public static bool SetEnabled(bool enabled, string runKeyPath = RunKeyPath)
        {
            try
            {
                using (var key = Registry.CurrentUser.CreateSubKey(runKeyPath))
                {
                    if (key == null) return false;
                    if (enabled)
                    {
                        key.SetValue(ValueName, CommandLine, RegistryValueKind.String);
                    }
                    else
                    {
                        if (key.GetValue(ValueName) != null) key.DeleteValue(ValueName, false);
                    }
                    return true;
                }
            }
            catch (Exception)
            {
                return false;
            }
        }
    }
}
