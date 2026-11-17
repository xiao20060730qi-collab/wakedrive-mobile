import SwiftUI

struct LoginView: View {
    let onLoggedIn: () -> Void
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    @State private var loading = false
    @State private var showRegister = false
    @State private var showAdmin = false
    @State private var editServer = false
    @State private var serverDraft = ApiClient.shared.baseUrl

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("登录 WakeDrive")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Theme.text)
                    .padding(.top, 70)
                Text("使用已注册的驾驶账号登录，监测数据保存到当前账号")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.muted)

                TextField("用户名", text: $username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(16)
                    .background(Theme.card)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.cardStroke))
                    .foregroundStyle(Theme.text)
                    .padding(.top, 26)

                SecureField("密码", text: $password)
                    .padding(16)
                    .background(Theme.card)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.cardStroke))
                    .foregroundStyle(Theme.text)

                if let error {
                    Text(error).font(.system(size: 12)).foregroundStyle(Theme.danger)
                }

                Button(action: submit) {
                    Text(loading ? "正在登录…" : "登录")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(
                            LinearGradient(colors: [Theme.cyan, Color(red: 47 / 255, green: 184 / 255, blue: 216 / 255)],
                                           startPoint: .leading, endPoint: .trailing)
                        )
                        .clipShape(Capsule())
                }
                .disabled(loading)
                .padding(.top, 8)

                Button { showAdmin = true } label: {
                    Text("管理员登录")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(
                            LinearGradient(colors: [Color(red: 90 / 255, green: 124 / 255, blue: 1),
                                                    Color(red: 138 / 255, green: 92 / 255, blue: 246 / 255)],
                                           startPoint: .leading, endPoint: .trailing)
                        )
                        .clipShape(Capsule())
                }

                HStack {
                    Spacer()
                    Button("还没有账号？立即注册") { showRegister = true }
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.cyan)
                    Spacer()
                }
                .padding(.top, 4)

                HStack {
                    Spacer()
                    Button("服务器：\(ApiClient.shared.baseUrl)（点击修改）") {
                        serverDraft = ApiClient.shared.baseUrl
                        editServer = true
                    }
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.muted)
                    Spacer()
                }
                .padding(.top, 8)
            }
            .padding(.horizontal, 28)
        }
        .background(Theme.background.ignoresSafeArea())
        .sheet(isPresented: $showRegister) {
            RegisterView(onRegistered: {
                showRegister = false
                onLoggedIn()
            })
        }
        .sheet(isPresented: $showAdmin) { AdminConsoleView() }
        .alert("后端地址", isPresented: $editServer) {
            TextField("https://wakedrive.xyz/api", text: $serverDraft)
            Button("保存") {
                ApiClient.shared.baseUrl = serverDraft
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("填写后端 API 根地址，例如 https://wakedrive.xyz/api 或 http://192.168.1.10:8000/api")
        }
    }

    private func submit() {
        error = nil
        let name = username.trimmingCharacters(in: .whitespaces)
        guard name.count >= 3, !password.isEmpty else {
            error = "请输入用户名和密码"
            return
        }
        loading = true
        Task {
            do {
                try await ApiClient.shared.login(username: name, password: password)
                await MainActor.run {
                    loading = false
                    onLoggedIn()
                }
            } catch {
                await MainActor.run {
                    loading = false
                    self.error = error.localizedDescription
                }
            }
        }
    }
}

struct RegisterView: View {
    let onRegistered: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    @State private var loading = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("注册 WakeDrive").font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.text).padding(.top, 40)
                Text("用户名 3-24 位，密码至少 8 位").font(.system(size: 13)).foregroundStyle(Theme.muted)
                TextField("用户名", text: $username)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .padding(16).background(Theme.card)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.cardStroke))
                    .foregroundStyle(Theme.text).padding(.top, 20)
                SecureField("密码", text: $password)
                    .padding(16).background(Theme.card)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.cardStroke))
                    .foregroundStyle(Theme.text)
                if let error { Text(error).font(.system(size: 12)).foregroundStyle(Theme.danger) }
                Button {
                    submit()
                } label: {
                    Text(loading ? "正在注册…" : "注册并登录")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(Color(red: 4 / 255, green: 19 / 255, blue: 27 / 255))
                        .frame(maxWidth: .infinity).frame(height: 54)
                        .background(LinearGradient(colors: [Theme.cyan, Color(red: 47 / 255, green: 184 / 255, blue: 216 / 255)],
                                                   startPoint: .leading, endPoint: .trailing))
                        .clipShape(Capsule())
                }.disabled(loading)
                Button("返回登录") { dismiss() }.font(.system(size: 13)).foregroundStyle(Theme.cyan)
            }
            .padding(.horizontal, 28)
        }
        .background(Theme.background.ignoresSafeArea())
    }

    private func submit() {
        error = nil
        let name = username.trimmingCharacters(in: .whitespaces)
        guard name.count >= 3 else { error = "用户名至少需要 3 位"; return }
        guard password.count >= 8 else { error = "密码至少需要 8 位"; return }
        loading = true
        Task {
            do {
                try await ApiClient.shared.register(username: name, password: password)
                await MainActor.run {
                    loading = false
                    onRegistered()
                }
            } catch {
                await MainActor.run {
                    loading = false
                    self.error = error.localizedDescription
                }
            }
        }
    }
}
