import SwiftUI
import UIKit

struct AIView: View {
    @State private var messages: [(String, String)] = [("ai", "你好，我可以回答通用问题，也可以分析驾驶安全。直接输入问题或点「分析当前状态」。")]
    @State private var input = ""
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("AI 驾驶分析").font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.text).padding(.top, 16)
            Text("可回答通用问题；登录后可按需结合当前账号的事件与设置。")
                .font(.system(size: 12)).foregroundStyle(Theme.muted)

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(Array(messages.enumerated()), id: \.offset) { index, message in
                            Text((message.0 == "ai" ? "WakeDrive AI\n" : "你\n") + message.1)
                                .font(.system(size: 12))
                                .foregroundStyle(message.0 == "ai" ? Theme.text : Theme.cyan)
                                .padding(12)
                                .background(message.0 == "ai" ? Theme.card : Color(red: 22 / 255, green: 77 / 255, blue: 104 / 255))
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                .id(index)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onChange(of: messages.count) { count in
                    withAnimation { proxy.scrollTo(count - 1, anchor: .bottom) }
                }
            }

            HStack(spacing: 8) {
                TextField("例如：如何预防疲劳驾驶", text: $input, axis: .vertical)
                    .lineLimit(1...3)
                    .padding(12)
                    .background(Theme.card)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.cardStroke))
                    .foregroundStyle(Theme.text)
                Button { send() } label: {
                    Text("发送").font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                        .padding(.horizontal, 18).frame(height: 44)
                        .background(Theme.cyan).clipShape(Capsule())
                }
            }
            Button {
                input = "分析当前驾驶员的疲劳状态并给出建议"
                send()
            } label: {
                Text("✦ 分析当前状态").font(.system(size: 13)).foregroundStyle(Theme.cyan)
                    .frame(maxWidth: .infinity).frame(height: 42)
                    .background(Theme.card).clipShape(Capsule())
                    .overlay(Capsule().stroke(Theme.cardStroke))
            }
        }
        .padding(.horizontal, 16).padding(.bottom, 12)
        .background(Theme.background.ignoresSafeArea())
    }

    private func send() {
        let question = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !question.isEmpty, !busy else { return }
        busy = true
        input = ""
        messages.append(("user", question))
        messages.append(("ai", "正在分析…"))
        Task {
            let reply = await ApiClient.shared.aiChat(question: question)
            var answer = reply
            if answer == nil { answer = await localFallback(question) }
            await MainActor.run {
                messages[messages.count - 1] = ("ai", answer ?? "在线 AI 暂不可用，请稍后重试。")
                busy = false
            }
        }
    }

    private func localFallback(_ question: String) async -> String {
        let events = (try? await ApiClient.shared.fetchEvents(limit: 50)) ?? []
        if question.contains("闭眼") || question.contains("眼睛") {
            let count = events.filter { $0["type"] as? String == "EYE_CLOSE" }.count
            return count > 0 ? "当前记录中检测到 \(count) 次闭眼事件，持续闭眼时请尽快停车休息。" : "当前记录中没有检测到闭眼事件。"
        }
        if question.contains("哈欠") {
            let count = events.filter { $0["type"] as? String == "YAWN" }.count
            return count > 0 ? "当前记录中检测到 \(count) 次打哈欠事件，短时间连续出现时请尽快休息。" : "当前记录中没有检测到打哈欠事件。"
        }
        if question.contains("预防") || question.contains("避免") {
            return "预防疲劳驾驶应先保证睡眠，避免长时间连续驾驶；每两小时停车休息至少 20 分钟；困倦时不要硬撑。"
        }
        return events.count >= 5
            ? "在线 AI 暂不可用。当前账号已有 \(events.count) 条事件记录，建议优先复核并在安全地点休息。"
            : "在线 AI 暂不可用。当前账号事件较少，请保持专注并按时休息。"
    }
}

struct SettingsView: View {
    let onLogout: () -> Void
    @State private var driverName = ""
    @State private var plateNumber = ""
    @State private var earThreshold = "0.25"
    @State private var marThreshold = "0.55"
    @State private var mildFrames = "2"
    @State private var mediumFrames = "5"
    @State private var heavyFrames = "9"
    @State private var cooldownMs = "5000"
    @State private var alarmEnabled = true
    @State private var alarmStyleIndex = 0
    @State private var status = ""
    @State private var showAdmin = false
    @State private var editServer = false
    @State private var serverDraft = ApiClient.shared.baseUrl
    @State private var isAdmin = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("系统设置").font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.text).padding(.top, 16)
                Text("设置保存到当前账号，并与网页端共用。").font(.system(size: 12)).foregroundStyle(Theme.muted)
                VStack(alignment: .leading, spacing: 10) {
                    field("驾驶员姓名", $driverName)
                    field("车牌号", $plateNumber)
                    field("EAR 闭眼阈值", $earThreshold, decimal: true)
                    field("MAR 张嘴阈值", $marThreshold, decimal: true)
                    field("轻度帧数", $mildFrames, decimal: false)
                    field("中度帧数", $mediumFrames, decimal: false)
                    field("重度帧数", $heavyFrames, decimal: false)
                    field("告警冷却（毫秒）", $cooldownMs, decimal: false)
                    Toggle("启用告警声", isOn: $alarmEnabled).tint(Theme.cyan).foregroundStyle(Theme.text)
                    Picker("告警风格", selection: $alarmStyleIndex) {
                        Text("bell").tag(0)
                        Text("silent").tag(1)
                    }
                    .pickerStyle(.segmented)
                }
                .wdCard()

                if !status.isEmpty { Text(status).font(.system(size: 12)).foregroundStyle(Theme.muted) }

                Button { Task { await save() } } label: {
                    Text("保存设置").font(.system(size: 16, weight: .bold))
                        .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                        .frame(maxWidth: .infinity).frame(height: 50)
                        .background(Theme.cyan).clipShape(Capsule())
                }

                HStack(spacing: 8) {
                    Button { testAlarm() } label: {
                        Text("测试警报").font(.system(size: 13)).foregroundStyle(Theme.text)
                            .frame(maxWidth: .infinity).frame(height: 44)
                            .background(Theme.card).clipShape(Capsule()).overlay(Capsule().stroke(Theme.cardStroke))
                    }
                    Button {
                        serverDraft = ApiClient.shared.baseUrl
                        editServer = true
                    } label: {
                        Text("服务器地址").font(.system(size: 13)).foregroundStyle(Theme.text)
                            .frame(maxWidth: .infinity).frame(height: 44)
                            .background(Theme.card).clipShape(Capsule()).overlay(Capsule().stroke(Theme.cardStroke))
                    }
                }

                if isAdmin {
                    Button { showAdmin = true } label: {
                        Text("管理员后台 →").font(.system(size: 14)).foregroundStyle(Theme.text)
                            .frame(maxWidth: .infinity).frame(height: 46)
                            .background(Theme.card).clipShape(Capsule()).overlay(Capsule().stroke(Theme.cardStroke))
                    }
                }

                Button {
                    Task {
                        await ApiClient.shared.logout()
                        onLogout()
                    }
                } label: {
                    Text("退出登录").font(.system(size: 14)).foregroundStyle(Color(red: 1, green: 202 / 255, blue: 210 / 255))
                        .frame(maxWidth: .infinity).frame(height: 46)
                        .background(Color(red: 58 / 255, green: 35 / 255, blue: 48 / 255))
                        .clipShape(Capsule())
                        .overlay(Capsule().stroke(Color(red: 140 / 255, green: 77 / 255, blue: 93 / 255)))
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .background(Theme.background.ignoresSafeArea())
        .sheet(isPresented: $showAdmin) { AdminConsoleView() }
        .alert("后端地址", isPresented: $editServer) {
            TextField("https://wakedrive.xyz/api", text: $serverDraft)
            Button("保存") { ApiClient.shared.baseUrl = serverDraft }
            Button("取消", role: .cancel) {}
        }
        .task { await load() }
    }

    private func field(_ label: String, _ binding: Binding<String>, decimal: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.system(size: 11)).foregroundStyle(Theme.muted)
            TextField(label, text: binding)
                .keyboardType(decimal ? .decimalPad : .default)
                .padding(10)
                .background(Theme.background)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.cardStroke))
                .foregroundStyle(Theme.text)
        }
    }

    private func load() async {
        isAdmin = (await ApiClient.shared.me()?["role"] as? String) == "admin"
        guard let settings = await ApiClient.shared.getSettings() else {
            status = "设置加载失败"
            return
        }
        await MainActor.run {
            driverName = settings["driverName"] as? String ?? ""
            plateNumber = settings["plateNumber"] as? String ?? ""
            earThreshold = String(settings["earThreshold"] as? Double ?? 0.25)
            marThreshold = String(settings["marThreshold"] as? Double ?? 0.55)
            mildFrames = String(settings["mildFrames"] as? Int ?? 2)
            mediumFrames = String(settings["mediumFrames"] as? Int ?? 5)
            heavyFrames = String(settings["heavyFrames"] as? Int ?? 9)
            cooldownMs = String(settings["cooldownMs"] as? Int ?? 5000)
            alarmEnabled = settings["alarmEnabled"] as? Bool ?? true
            alarmStyleIndex = (settings["alarmStyle"] as? String) == "silent" ? 1 : 0
        }
    }

    private func save() async {
        var patch: [String: Any] = [
            "driverName": driverName.trimmingCharacters(in: .whitespaces),
            "plateNumber": plateNumber.trimmingCharacters(in: .whitespaces),
            "alarmEnabled": alarmEnabled,
            "alarmStyle": alarmStyleIndex == 1 ? "silent" : "bell",
        ]
        if let value = Double(earThreshold) { patch["earThreshold"] = value }
        if let value = Double(marThreshold) { patch["marThreshold"] = value }
        if let value = Int(mildFrames) { patch["mildFrames"] = value }
        if let value = Int(mediumFrames) { patch["mediumFrames"] = value }
        if let value = Int(heavyFrames) { patch["heavyFrames"] = value }
        if let value = Int(cooldownMs) { patch["cooldownMs"] = value }
        do {
            try await ApiClient.shared.putSettings(patch)
            await MainActor.run { status = "设置已保存" }
        } catch {
            await MainActor.run { status = error.localizedDescription }
        }
    }

    private func testAlarm() {
        let alarm = AlarmManager()
        alarm.configure(enabled: alarmEnabled, style: alarmStyleIndex == 1 ? "silent" : "bell", cooldownMs: 3000)
        alarm.event("harsh", "测试警报：重度疲劳，请立即停车休息。")
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) { alarm.stop() }
    }
}
