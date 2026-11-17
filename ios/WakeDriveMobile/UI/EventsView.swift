import SwiftUI

struct EventsView: View {
    @State private var events: [[String: Any]] = []
    @State private var severity = ""
    @State private var type = ""
    @State private var statusText = "加载中…"
    @State private var selected: [String: Any]?
    @State private var confirmClear = false

    private let severityOptions = [("全部等级", ""), ("轻度疲劳", "轻度疲劳"), ("中度疲劳", "中度疲劳"), ("重度疲劳", "重度疲劳")]
    private let typeOptions = [
        ("全部类型", ""), ("闭眼", "EYE_CLOSE"), ("打哈欠", "YAWN"), ("使用手机", "PHONE_USE"),
        ("低头", "HEAD_DOWN"), ("视线偏离", "HEAD_AWAY"), ("未检测到人脸", "FACE_LOST"), ("喝水/抬手", "DRINK"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("事件记录").font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.text).padding(.top, 16)
                Text("查看、筛选和处理当前账号的疲劳事件（含抓拍图片）")
                    .font(.system(size: 12)).foregroundStyle(Theme.muted)
                VStack(spacing: 8) {
                    HStack {
                        Menu {
                            ForEach(severityOptions, id: \.1) { item in
                                Button(item.0) { severity = item.1; Task { await load() } }
                            }
                        } label: { filterLabel(severityOptions.first { $0.1 == severity }?.0 ?? "全部等级") }
                        Menu {
                            ForEach(typeOptions, id: \.1) { item in
                                Button(item.0) { type = item.1; Task { await load() } }
                            }
                        } label: { filterLabel(typeOptions.first { $0.1 == type }?.0 ?? "全部类型") }
                    }
                    HStack(spacing: 8) {
                        ShareLink(item: csvText) {
                            Text("导出 CSV").font(.system(size: 13)).foregroundStyle(Theme.text)
                                .frame(maxWidth: .infinity).frame(height: 42)
                                .background(Theme.card).clipShape(RoundedRectangle(cornerRadius: 12))
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.cardStroke))
                        }
                        Button { confirmClear = true } label: {
                            Text("清空全部").font(.system(size: 13)).foregroundStyle(Theme.danger)
                                .frame(maxWidth: .infinity).frame(height: 42)
                                .background(Theme.card).clipShape(RoundedRectangle(cornerRadius: 12))
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 140 / 255, green: 77 / 255, blue: 93 / 255)))
                        }
                    }
                }
                .wdCard()

                Text(statusText).font(.system(size: 12)).foregroundStyle(Theme.muted)

                ForEach(Array(events.enumerated()), id: \.offset) { _, event in
                    Button { selected = event } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(typeLabel(event["type"] as? String ?? "")).font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                                Spacer()
                                Text(event["severity"] as? String ?? "")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundStyle(severityColor(event["severity"] as? String ?? ""))
                            }
                            Text((event["createdAt"] as? String ?? "").prefix(19).replacingOccurrences(of: "T", with: " "))
                                .font(.system(size: 11)).foregroundStyle(Theme.muted)
                            Text(event["message"] as? String ?? "—").font(.system(size: 12)).foregroundStyle(Theme.muted)
                        }
                        .wdCard()
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
        .background(Theme.background.ignoresSafeArea())
        .task { await load() }
        .sheet(item: Binding(get: { selected.map(EventBox.init) }, set: { if $0 == nil { selected = nil } })) { box in
            EventDetailView(event: box.event, onChanged: { Task { await load() } })
        }
        .alert("清空事件", isPresented: $confirmClear) {
            Button("清空", role: .destructive) {
                Task {
                    _ = await ApiClient.shared.clearEvents()
                    await load()
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("确定删除当前账号的全部事件记录吗？此操作无法撤销。")
        }
    }

    private func filterLabel(_ text: String) -> some View {
        Text(text + " ▾")
            .font(.system(size: 13)).foregroundStyle(Theme.text)
            .frame(maxWidth: .infinity).frame(height: 40)
            .background(Theme.card).clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.cardStroke))
    }

    private var csvText: String {
        var text = "发生时间,事件类型,风险等级,处理状态,说明\n"
        for event in events {
            text += "\(event["createdAt"] as? String ?? ""),\(typeLabel(event["type"] as? String ?? "")),\(event["severity"] as? String ?? ""),\(event["status"] as? String ?? ""),\((event["message"] as? String ?? "").replacingOccurrences(of: ",", with: "，"))\n"
        }
        return text
    }

    private func load() async {
        statusText = "加载中…"
        events = (try? await ApiClient.shared.fetchEvents(
            limit: 100,
            severity: severity.isEmpty ? nil : severity,
            type: type.isEmpty ? nil : type
        )) ?? []
        statusText = events.isEmpty ? "暂无符合条件的事件" : "共 \(events.count) 条事件"
    }

    private func typeLabel(_ type: String) -> String {
        ShellViewLabels.types[type] ?? "疲劳事件"
    }

    private func severityColor(_ severity: String) -> Color {
        severity == "重度疲劳" ? Theme.danger : severity == "中度疲劳" ? Theme.warning : Theme.safe
    }
}

struct EventBox: Identifiable {
    let event: [String: Any]
    var id: String { (event["id"] as? String) ?? UUID().uuidString }
}

struct EventDetailView: View {
    let event: [String: Any]
    let onChanged: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var snapshot: UIImage?

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(ShellViewLabels.types[event["type"] as? String ?? ""] ?? "疲劳事件")
                        .font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.text)
                    Text(event["message"] as? String ?? "—").font(.system(size: 13)).foregroundStyle(Theme.text)
                    Text("发生时间：\((event["createdAt"] as? String ?? "").prefix(19).replacingOccurrences(of: "T", with: " "))")
                        .font(.system(size: 12)).foregroundStyle(Theme.muted)
                    Text("状态：\(event["status"] as? String ?? "待处理") · EAR \(String(format: "%.2f", event["earValue"] as? Double ?? 0)) · MAR \(String(format: "%.2f", event["marValue"] as? Double ?? 0))")
                        .font(.system(size: 12)).foregroundStyle(Theme.muted)
                    if let snapshot {
                        Image(uiImage: snapshot).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    Button {
                        Task {
                            if let id = event["id"] as? String, await ApiClient.shared.acknowledgeEvent(id: id) {
                                onChanged()
                                dismiss()
                            }
                        }
                    } label: {
                        Text(event["status"] as? String == "已处理" ? "已处理" : "标记已处理")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                            .frame(maxWidth: .infinity).frame(height: 48)
                            .background(Theme.cyan).clipShape(Capsule())
                    }
                    .disabled(event["status"] as? String == "已处理")
                }
                .padding(20)
            }
            .background(Theme.background.ignoresSafeArea())
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
        .onAppear {
            if let base64 = event["snapshot"] as? String, base64.hasPrefix("data:image/"),
               let data = Data(base64Encoded: String(base64.split(separator: ",").last ?? "")) {
                snapshot = UIImage(data: data)
            }
        }
    }
}

enum ShellViewLabels {
    static let types = [
        "EYE_CLOSE": "持续闭眼", "YAWN": "打哈欠", "PHONE_USE": "分心使用手机",
        "HEAD_DOWN": "低头", "HEAD_AWAY": "视线偏离", "FACE_LOST": "未检测到人脸", "DRINK": "喝水/抬手动作",
    ]
}
