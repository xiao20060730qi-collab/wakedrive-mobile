import SwiftUI
import UIKit

struct AdminConsoleView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var admin: [String: Any]?
    @State private var users: [[String: Any]] = []
    @State private var status = "请先登录管理员账号"
    @State private var username = ""
    @State private var password = ""
    @State private var loggingIn = false
    @State private var eventUser: [String: Any]?
    @State private var eventList: [[String: Any]] = []
    @State private var showEvents = false
    @State private var deleteUserTarget: [String: Any]?
    @State private var showDeleteUser = false
    @State private var adminList: [[String: Any]] = []
    @State private var showAdminManage = false
    @State private var newAdminName = ""
    @State private var newAdminPass = ""
    @State private var showAddAdmin = false
    @State private var resetAdminTarget: [String: Any]?
    @State private var resetPass = ""
    @State private var showResetAdmin = false
    @State private var adminMessage = ""

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if admin == nil {
                        loginForm
                    } else {
                        HStack {
                            Text("管理员后台 · \(admin?["username"] as? String ?? "")")
                                .font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.text)
                            Spacer()
                            Button("刷新") { Task { await loadUsers() } }
                                .foregroundStyle(Theme.cyan)
                        }
                        Text(status).font(.system(size: 12)).foregroundStyle(Theme.muted)
                        ForEach(Array(users.enumerated()), id: \.offset) { _, user in
                            userCard(user)
                        }
                        if (admin?["username"] as? String)?.lowercased() == "xiaoqi" {
                            Button {
                                Task { await loadAdmins() }
                                showAdminManage = true
                            } label: {
                                Text("⚙ 管理员账号管理（xiaoqi）")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                                    .frame(maxWidth: .infinity).frame(height: 48)
                                    .background(Theme.cyan).clipShape(Capsule())
                            }
                        }
                        if !adminMessage.isEmpty {
                            Text(adminMessage).font(.system(size: 12)).foregroundStyle(Theme.muted)
                        }
                    }
                }
                .padding(16)
            }
            .background(Theme.background.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                if admin != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("退出") {
                            Task {
                                await ApiClient.shared.adminLogout()
                                admin = nil
                                users = []
                                status = "请先登录管理员账号"
                            }
                        }
                    }
                }
            }
            .sheet(isPresented: $showEvents) { eventSheet }
            .sheet(isPresented: $showAdminManage) { adminManageSheet }
            .alert("删除账号", isPresented: $showDeleteUser) {
                Button("删除", role: .destructive) {
                    Task { await deleteUser() }
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text("确定删除账号「\(deleteUserTarget?["username"] as? String ?? "")」及其全部疲劳事件吗？")
            }
        }
    }

    private var loginForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("管理员登录").font(.system(size: 18, weight: .bold)).foregroundStyle(Theme.text).padding(.top, 8)
            TextField("管理员账号", text: $username)
                .textInputAutocapitalization(.never)
                .padding(14).background(Theme.card)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.cardStroke))
                .foregroundStyle(Theme.text)
            SecureField("密码", text: $password)
                .padding(14).background(Theme.card)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.cardStroke))
                .foregroundStyle(Theme.text)
            Button {
                Task { await login() }
            } label: {
                Text(loggingIn ? "正在登录…" : "登录")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                    .frame(maxWidth: .infinity).frame(height: 48)
                    .background(Theme.cyan).clipShape(Capsule())
            }
            .disabled(loggingIn)
            Text(status).font(.system(size: 12)).foregroundStyle(Theme.muted)
        }
        .wdCard()
        .padding(.top, 20)
    }

    private func userCard(_ user: [String: Any]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(user["username"] as? String ?? "").font(.system(size: 15, weight: .bold)).foregroundStyle(Theme.text)
                Text("[\(user["role"] as? String ?? "user")]").font(.system(size: 11)).foregroundStyle(Theme.muted)
                Spacer()
                Text((user["latest"] as? [String: Any])?["severity"] as? String ?? "未监测")
                    .font(.system(size: 11)).foregroundStyle(Theme.muted)
            }
            HStack(spacing: 8) {
                Button("查看事件") {
                    eventUser = user
                    Task { await loadEvents(user) }
                }
                .font(.system(size: 13)).foregroundStyle(Theme.text)
                if user["role"] as? String != "admin" {
                    Button("删除账号") {
                        deleteUserTarget = user
                        showDeleteUser = true
                    }
                    .font(.system(size: 13)).foregroundStyle(Theme.danger)
                }
            }
        }
        .wdCard()
    }

    private var eventSheet: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(eventList.enumerated()), id: \.offset) { _, event in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(ShellViewLabels.types[event["type"] as? String ?? ""] ?? "事件") · \(event["severity"] as? String ?? "") · \((event["createdAt"] as? String ?? "").prefix(19).replacingOccurrences(of: "T", with: " "))")
                                .font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.text)
                            Text(event["message"] as? String ?? "").font(.system(size: 11)).foregroundStyle(Theme.muted)
                            if let base64 = event["snapshot"] as? String, base64.hasPrefix("data:image/"),
                               let data = Data(base64Encoded: String(base64.split(separator: ",").last ?? "")),
                               let image = UIImage(data: data) {
                                Image(uiImage: image).resizable().scaledToFit()
                                    .frame(maxHeight: 160).clipShape(RoundedRectangle(cornerRadius: 10))
                            }
                        }
                        .wdCard()
                    }
                    if eventList.isEmpty {
                        Text("该账户暂无疲劳事件").font(.system(size: 12)).foregroundStyle(Theme.muted)
                    }
                }
                .padding(16)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("\(eventUser?["username"] as? String ?? "") 的疲劳事件")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { showEvents = false } } }
        }
    }

    private var adminManageSheet: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(adminList.enumerated()), id: \.offset) { _, item in
                        HStack {
                            Text(item["username"] as? String ?? "")
                                .font(.system(size: 14)).foregroundStyle(Theme.text)
                            if item["id"] as? Int == admin?["id"] as? Int {
                                Text("（当前登录）").font(.system(size: 11)).foregroundStyle(Theme.muted)
                            }
                            Spacer()
                            Button("改密") {
                                resetAdminTarget = item
                                resetPass = ""
                                showResetAdmin = true
                            }
                            .font(.system(size: 12)).foregroundStyle(Theme.cyan)
                            if item["id"] as? Int != admin?["id"] as? Int {
                                Button("删除") {
                                    if let id = item["id"] as? Int {
                                        Task {
                                            try? await ApiClient.shared.adminDeleteAdmin(adminId: id)
                                            await loadAdmins()
                                        }
                                    }
                                }
                                .font(.system(size: 12)).foregroundStyle(Theme.danger)
                            }
                        }
                        .wdCard()
                    }
                    Button {
                        newAdminName = ""
                        newAdminPass = ""
                        showAddAdmin = true
                    } label: {
                        Text("新增管理员")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                            .frame(maxWidth: .infinity).frame(height: 44)
                            .background(Theme.cyan).clipShape(Capsule())
                    }
                    if !adminMessage.isEmpty {
                        Text(adminMessage).font(.system(size: 12)).foregroundStyle(Theme.muted)
                    }
                }
                .padding(16)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle("管理员账号管理")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { showAdminManage = false } } }
            .alert("新增管理员", isPresented: $showAddAdmin) {
                TextField("用户名（3-24 位）", text: $newAdminName)
                TextField("初始密码（至少 8 位）", text: $newAdminPass)
                Button("创建") {
                    Task {
                        do {
                            try await ApiClient.shared.adminCreateAdmin(username: newAdminName, password: newAdminPass)
                            adminMessage = "已新增管理员 \(newAdminName)"
                            await loadAdmins()
                        } catch {
                            adminMessage = error.localizedDescription
                        }
                    }
                }
                Button("取消", role: .cancel) {}
            }
            .alert("重置密码", isPresented: $showResetAdmin) {
                TextField("新密码（至少 8 位）", text: $resetPass)
                Button("保存") {
                    if let id = resetAdminTarget?["id"] as? Int {
                        Task {
                            do {
                                try await ApiClient.shared.adminResetPassword(adminId: id, password: resetPass)
                                adminMessage = "已重置 \(resetAdminTarget?["username"] as? String ?? "") 的密码"
                            } catch {
                                adminMessage = error.localizedDescription
                            }
                        }
                    }
                }
                Button("取消", role: .cancel) {}
            }
        }
    }

    private func login() async {
        loggingIn = true
        do {
            try await ApiClient.shared.adminLogin(username: username, password: password)
            admin = await ApiClient.shared.adminMe()
            status = "管理员身份验证成功"
            await loadUsers()
        } catch {
            status = error.localizedDescription
        }
        loggingIn = false
    }

    private func loadUsers() async {
        let list = await ApiClient.shared.adminUsers()
        await MainActor.run {
            users = list
            status = list.isEmpty ? "暂无账户" : "共 \(list.count) 个账户 · 点击查看事件"
        }
    }

    private func loadEvents(_ user: [String: Any]) async {
        guard let id = user["id"] as? Int else { return }
        let list = await ApiClient.shared.adminUserEvents(userId: id)
        await MainActor.run {
            eventList = list
            showEvents = true
        }
    }

    private func deleteUser() async {
        guard let id = deleteUserTarget?["id"] as? Int else { return }
        let ok = await ApiClient.shared.adminDeleteUser(userId: id)
        await MainActor.run {
            status = ok ? "已删除账号" : "删除失败"
            if ok { Task { await loadUsers() } }
        }
    }

    private func loadAdmins() async {
        do {
            adminList = try await ApiClient.shared.adminListAdmins()
        } catch {
            adminMessage = error.localizedDescription
        }
    }
}
