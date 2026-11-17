import SwiftUI

struct ReportsView: View {
    @State private var reports: [[String: Any]] = []
    @State private var status = "加载中…"
    @State private var detail: [String: Any]?
    @State private var showDetail = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("监测报告").font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.text).padding(.top, 16)
                Text("每次结束监测都会生成一份报告：风险曲线、事件与覆盖时长。")
                    .font(.system(size: 12)).foregroundStyle(Theme.muted)
                Text(status).font(.system(size: 12)).foregroundStyle(Theme.muted)
                ForEach(Array(reports.enumerated()), id: \.offset) { _, report in
                    Button {
                        Task { await loadDetail(report["id"] as? String ?? "") }
                    } label: {
                        let summary = report["summary"] as? [String: Any] ?? [:]
                        VStack(alignment: .leading, spacing: 4) {
                            Text(report["title"] as? String ?? "监测报告")
                                .font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                            Text("开始：\((report["startedAt"] as? String ?? "").prefix(19).replacingOccurrences(of: "T", with: " "))")
                                .font(.system(size: 11)).foregroundStyle(Theme.muted)
                            Text("覆盖 \(duration(summary["durationMs"] as? Int ?? 0)) · 事件 \(summary["eventCount"] as? Int ?? 0) 个 · 峰值 \(summary["peakScore"] as? Int ?? 0)")
                                .font(.system(size: 11)).foregroundStyle(Theme.muted)
                        }
                        .wdCard()
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 20)
        }
        .background(Theme.background.ignoresSafeArea())
        .task { await load() }
        .sheet(isPresented: $showDetail) {
            if let detail { ReportDetailView(report: detail) }
        }
    }

    private func duration(_ ms: Int) -> String {
        "\(ms / 60000) 分 \((ms / 1000) % 60) 秒"
    }

    private func load() async {
        reports = await ApiClient.shared.fetchReports()
        status = reports.isEmpty ? "暂无报告，结束一次监测后自动生成" : "共 \(reports.count) 份报告"
    }

    private func loadDetail(_ id: String) async {
        guard !id.isEmpty else { return }
        detail = await ApiClient.shared.fetchReport(id: id)
        showDetail = detail != nil
    }
}

struct ReportDetailView: View {
    let report: [String: Any]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    let summary = report["summary"] as? [String: Any] ?? [:]
                    let samples = report["samples"] as? [[String: Any]] ?? []
                    RiskCurve(samples: samples).frame(height: 160)
                    Text("峰值 \(summary["peakScore"] as? Int ?? 0) · 事件 \(summary["eventCount"] as? Int ?? 0) · 覆盖率 \(Int((summary["coverage"] as? Double ?? 0) * 100))%")
                        .font(.system(size: 12)).foregroundStyle(Theme.muted)
                    let severity = summary["severityCounts"] as? [String: Any] ?? [:]
                    Text("轻度 \(severity["mild"] as? Int ?? 0) · 中度 \(severity["medium"] as? Int ?? 0) · 重度 \(severity["high"] as? Int ?? 0)")
                        .font(.system(size: 12)).foregroundStyle(Theme.muted)
                    ForEach(Array(((report["events"] as? [[String: Any]] ?? []).prefix(10)).enumerated()), id: \.offset) { _, event in
                        Text("\(((event["offsetMs"] as? Int ?? 0) / 1000))s · \(ShellViewLabels.types[event["type"] as? String ?? ""] ?? "事件") · \(event["severity"] as? String ?? "")")
                            .font(.system(size: 11)).foregroundStyle(Theme.muted)
                    }
                }
                .padding(20)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("报告详情")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
    }
}

struct RiskCurve: View {
    let samples: [[String: Any]]

    var body: some View {
        Canvas { context, size in
            guard !samples.isEmpty else { return }
            var duration = 0
            for sample in samples {
                duration = max(duration, (sample["offsetMs"] as? Int ?? 0) + (sample["spanMs"] as? Int ?? 0))
            }
            guard duration > 0 else { return }
            for level in stride(from: 30, through: 90, by: 30) {
                let y = size.height - CGFloat(level) / 100 * size.height
                var line = Path()
                line.move(to: CGPoint(x: 0, y: y))
                line.addLine(to: CGPoint(x: size.width, y: y))
                context.stroke(line, with: .color(Theme.cardStroke.opacity(0.5)), lineWidth: 1)
            }
            var path = Path()
            var started = false
            for sample in samples {
                guard (sample["validMs"] as? Int ?? 0) > 0,
                      let score = sample["score"] as? Int else {
                    started = false
                    continue
                }
                let x = CGFloat(sample["offsetMs"] as? Int ?? 0) / CGFloat(duration) * size.width
                let y = size.height - CGFloat(score) / 100 * size.height
                if !started {
                    path.move(to: CGPoint(x: x, y: y))
                    started = true
                } else {
                    path.addLine(to: CGPoint(x: x, y: y))
                }
            }
            context.stroke(path, with: .color(Theme.cyan), lineWidth: 2.5)
        }
        .background(Theme.card)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

struct StatsView: View {
    @State private var range = "7d"
    @State private var summary: [String: Any] = [:]
    @State private var loading = true

    private let ranges = [("today", "今日"), ("7d", "近 7 天"), ("30d", "近 30 天")]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("统计分析").font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.text).padding(.top, 16)
                HStack(spacing: 8) {
                    ForEach(ranges, id: \.0) { item in
                        Button(item.1) {
                            range = item.0
                            Task { await load() }
                        }
                        .font(.system(size: 13))
                        .foregroundStyle(range == item.0 ? Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255) : Theme.text)
                        .frame(maxWidth: .infinity).frame(height: 40)
                        .background(range == item.0 ? Theme.cyan : Theme.card)
                        .clipShape(Capsule())
                        .overlay(Capsule().stroke(Theme.cardStroke))
                    }
                }
                Text(loading ? "加载中…" : "总记录 \(summary["totalRecords"] as? Int ?? 0) 条")
                    .font(.system(size: 12)).foregroundStyle(Theme.muted)

                let severity = summary["severityDistribution"] as? [String: Any] ?? [:]
                VStack(alignment: .leading, spacing: 8) {
                    Text("风险等级分布").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                    bar("轻度疲劳", severity["轻度疲劳"] as? Int ?? 0, Theme.safe)
                    bar("中度疲劳", severity["中度疲劳"] as? Int ?? 0, Theme.warning)
                    bar("重度疲劳", severity["重度疲劳"] as? Int ?? 0, Theme.danger)
                }
                .wdCard()

                let types = summary["typeDistribution"] as? [String: Any] ?? [:]
                VStack(alignment: .leading, spacing: 8) {
                    Text("事件类型分布").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                    ForEach(Array(ShellViewLabels.types.keys.sorted()), id: \.self) { key in
                        bar(ShellViewLabels.types[key] ?? key, types[key] as? Int ?? 0, Theme.cyan)
                    }
                }
                .wdCard()

                if let trend = summary["trend"] as? [[String: Any]] {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("近 7 天趋势").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                        ForEach(Array(trend.enumerated()), id: \.offset) { _, item in
                            Text("\(item["label"] as? String ?? "")  \(item["count"] as? Int ?? 0) 条")
                                .font(.system(size: 12)).foregroundStyle(Theme.muted)
                        }
                    }
                    .wdCard()
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 20)
        }
        .background(Theme.background.ignoresSafeArea())
        .task { await load() }
    }

    private func bar(_ label: String, _ value: Int, _ color: Color) -> some View {
        HStack(spacing: 8) {
            Text(label).font(.system(size: 12)).foregroundStyle(Theme.muted).frame(width: 96, alignment: .leading)
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.cardStroke.opacity(0.4))
                    Capsule().fill(color).frame(width: max(6, geometry.size.width * CGFloat(min(100, value)) / 100))
                }
            }
            .frame(height: 8)
            Text("\(value)").font(.system(size: 12)).foregroundStyle(Theme.text)
        }
    }

    private func load() async {
        loading = true
        summary = await ApiClient.shared.summary(range: range) ?? [:]
        loading = false
    }
}
