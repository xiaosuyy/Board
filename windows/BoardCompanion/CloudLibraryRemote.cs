using System;
using System.Collections.Generic;
using System.Web.Script.Serialization;

namespace BoardCompanion
{
    /// <summary>
    /// Cloud Library 的 WebDAV 实现（Phase 1-H）。
    ///
    /// **枚举 index 的约定**：本阶段只被授权新增 `DELETE`（不新增 PROPFIND），
    /// 因此无法列目录 → 用一个**设备注册表** `library/devices.json`（各设备自报 id 的并集）来确定
    /// 需要读取哪些 `library/index-&lt;deviceId&gt;.json`。
    ///
    /// 与 Clipboard **完全隔离**：只读写 `library/`。
    /// </summary>
    public sealed class CloudLibraryRemote : ICloudLibraryRemote
    {
        private const string DevicesPath = "library/devices.json";

        private readonly WebDavClient client;
        private readonly string myDeviceId;

        public CloudLibraryRemote(WebDavClient client, string myDeviceId)
        {
            this.client = client;
            this.myDeviceId = CloudLibrary.SanitizeToken(myDeviceId);
        }

        /// <summary>确保自己在设备注册表里（读改写，取并集，不删除别人）</summary>
        public void EnsureRegistered()
        {
            var ids = ReadDeviceIds();
            if (ids.Contains(myDeviceId)) return;
            ids.Add(myDeviceId);
            var json = new JavaScriptSerializer().Serialize(new Dictionary<string, object>
            {
                { "schema", 1 },
                { "devices", ids },
            });
            client.PutText(DevicesPath, json, "application/json; charset=utf-8");
        }

        public List<string> ReadDeviceIds()
        {
            var ids = new List<string>();
            try
            {
                var response = client.Get(DevicesPath);
                if (!response.IsOk || string.IsNullOrEmpty(response.Body)) return ids;
                var parsed = new JavaScriptSerializer()
                    .Deserialize<Dictionary<string, object>>(response.Body);
                object raw;
                if (parsed != null && parsed.TryGetValue("devices", out raw))
                {
                    var array = raw as System.Collections.ArrayList;
                    if (array != null)
                    {
                        foreach (var entry in array)
                        {
                            var id = CloudLibrary.SanitizeToken(Convert.ToString(entry));
                            if (!string.IsNullOrEmpty(id) && !ids.Contains(id)) ids.Add(id);
                        }
                    }
                }
            }
            catch (Exception)
            {
                // 坏 JSON / 网络失败 → 当作“只有自己”
            }
            if (!ids.Contains(myDeviceId)) ids.Add(myDeviceId);
            return ids;
        }

        public List<string> ReadAllIndexJson()
        {
            var result = new List<string>();
            var devices = ReadDeviceIds();
            Log.Info("library indexes devices=" + devices.Count);
            foreach (var deviceId in devices)
            {
                var json = ReadIndexJson(deviceId);
                Log.Info("library index device=" + CloudLibrary.ShortDevice(deviceId) +
                         " present=" + (!string.IsNullOrEmpty(json)));
                if (!string.IsNullOrEmpty(json)) result.Add(json);
            }
            return result;
        }

        public string ReadIndexJson(string deviceId)
        {
            var response = client.Get(CloudLibrary.IndexPathFor(deviceId));
            if (!response.IsOk || string.IsNullOrEmpty(response.Body)) return null;
            return response.Body;
        }

        // 注意：路径参数已经是 `CloudLibrary.BinaryPathFor()` 得到的 **`library/<name>`**，
        // 这里**绝不能再拼一次 `library/`**（否则会写到 `library/library/<name>`）。
        public byte[] GetBytes(string binaryName)
        {
            var response = client.GetBytes(binaryName.TrimStart('/'));
            return response.IsOk ? response.BodyBytes : null;
        }

        public bool PutBytes(string binaryName, byte[] bytes, string contentType)
        {
            var result = client.PutBytes(binaryName.TrimStart('/'), bytes, contentType);
            return result.IsOk;
        }

        public bool PutIndexJson(string deviceId, string json)
        {
            EnsureRegistered();
            var result = client.PutText(
                CloudLibrary.IndexPathFor(deviceId), json, "application/json; charset=utf-8");
            return result.IsOk;
        }

        /// <summary>200/204 = 成功；**404 = 成功**（幂等）；其余 = 失败</summary>
        public LibraryDeleteResult DeleteBinary(string binaryName)
        {
            var result = client.Delete(binaryName.TrimStart('/'));
            if (result.IsOk) return LibraryDeleteResult.Deleted;
            if (result.NotFound) return LibraryDeleteResult.AlreadyGone;
            return LibraryDeleteResult.Failed;
        }
    }
}
