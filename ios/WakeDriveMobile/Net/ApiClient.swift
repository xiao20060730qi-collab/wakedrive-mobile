import Foundation

/// Thin client for the existing Python backend (cookie based session, same endpoints as web/Android).
final class ApiClient {
    static let shared = ApiClient()
    static let defaultBase = "https://wakedrive.xyz/api"

    private let session: URLSession
    private let defaults = UserDefaults.standard

    private init() {
        let configuration = URLSessionConfiguration.default
        configuration.httpCookieStorage = HTTPCookieStorage.shared
        configuration.httpCookieAcceptPolicy = .always
        configuration.timeoutIntervalForRequest = 20
        session = URLSession(configuration: configuration)
    }

    var baseUrl: String {
        get { defaults.string(forKey: "baseUrl") ?? Self.defaultBase }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/")), forKey: "baseUrl") }
    }

    var isLoggedIn: Bool {
        HTTPCookieStorage.shared.cookies?.contains { cookie in
            cookie.name == "wakedrive_session"
        } ?? false
    }

    struct ApiError: LocalizedError {
        let status: Int
        let message: String
        var errorDescription: String? { message }
    }

    private func request(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> Any {
        guard let url = URL(string: baseUrl + path) else { throw ApiError(status: 0, message: "服务器地址无效") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = try? JSONSerialization.jsonObject(with: data)
        guard (200..<300).contains(status) else {
            let detail = (json as? [String: Any])?["detail"] as? String
            throw ApiError(status: status, message: detail ?? "请求失败（HTTP \(status)）")
        }
        return json ?? [:]
    }

    private func post(_ path: String, body: [String: Any]) async throws -> Any {
        try await request(path, method: "POST", body: body)
    }

    private func put(_ path: String, body: [String: Any]) async throws -> Any {
        try await request(path, method: "PUT", body: body)
    }

    private func patch(_ path: String, body: [String: Any]) async throws -> Any {
        try await request(path, method: "PATCH", body: body)
    }

    // MARK: auth

    func login(username: String, password: String) async throws {
        _ = try await post("/auth/login", body: ["username": username, "password": password])
    }

    func register(username: String, password: String) async throws {
        _ = try await post("/auth/register", body: ["username": username, "password": password])
    }

    func logout() async {
        _ = try? await post("/auth/logout", body: [:])
        clearSession()
    }

    func clearSession() {
        for cookie in HTTPCookieStorage.shared.cookies ?? [] where cookie.name == "wakedrive_session" {
            HTTPCookieStorage.shared.deleteCookie(cookie)
        }
    }

    func me() async -> [String: Any]? {
        guard let result = try? await request("/auth/me") as? [String: Any] else { return nil }
        return result["user"] as? [String: Any]
    }

    // MARK: settings

    func getSettings() async -> [String: Any]? {
        try? await request("/settings") as? [String: Any]
    }

    func putSettings(_ patch: [String: Any]) async throws {
        _ = try await put("/settings", body: patch)
    }

    // MARK: events

    func createEvent(_ event: [String: Any]) async throws {
        _ = try await post("/events", body: event)
    }

    func fetchEvents(limit: Int = 100, severity: String? = nil, type: String? = nil) async throws -> [[String: Any]] {
        var path = "/events?limit=\(limit)"
        if let severity, let encoded = severity.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) {
            path += "&severity=\(encoded)"
        }
        if let type { path += "&type=\(type)" }
        guard let result = try await request(path) as? [String: Any] else { return [] }
        return (result["items"] as? [[String: Any]]) ?? []
    }

    func acknowledgeEvent(id: String) async -> Bool {
        (try? await patch("/events/\(id)", body: ["status": "已处理", "handledBy": "iOS 管理员"])) != nil
    }

    func clearEvents() async -> Bool {
        (try? await request("/events", method: "DELETE")) != nil
    }

    // MARK: analysis & reports

    func summary(range: String) async -> [String: Any]? {
        try? await request("/analysis/summary?range=\(range)") as? [String: Any]
    }

    func createReport(_ report: [String: Any]) async throws {
        _ = try await post("/reports", body: report)
    }

    func fetchReports(limit: Int = 50) async -> [[String: Any]] {
        guard let result = try? await request("/reports?limit=\(limit)") as? [String: Any] else { return [] }
        return (result["items"] as? [[String: Any]]) ?? []
    }

    func fetchReport(id: String) async -> [String: Any]? {
        try? await request("/reports/\(id)") as? [String: Any]
    }

    // MARK: AI & rest stops

    func aiChat(question: String) async -> String? {
        guard let result = try? await post("/ai/chat", body: ["question": question]) as? [String: Any] else { return nil }
        let answer = result["answer"] as? String
        return (answer?.isEmpty == false) ? answer : nil
    }

    func restStops(lat: Double, lng: Double) async -> [[String: Any]] {
        guard let result = try? await request("/rest-stops?lat=\(lat)&lng=\(lng)") as? [String: Any] else { return [] }
        return (result["items"] as? [[String: Any]]) ?? []
    }

    // MARK: admin

    func adminLogin(username: String, password: String) async throws {
        _ = try await post("/admin/login", body: ["username": username, "password": password])
    }

    func adminLogout() async { _ = try? await post("/admin/logout", body: [:]) }

    func adminMe() async -> [String: Any]? {
        guard let result = try? await request("/admin/me") as? [String: Any] else { return nil }
        return result["user"] as? [String: Any]
    }

    func adminUsers(limit: Int = 100) async -> [[String: Any]] {
        guard let result = try? await request("/admin/users?limit=\(limit)") as? [String: Any] else { return [] }
        return (result["items"] as? [[String: Any]]) ?? []
    }

    func adminUserEvents(userId: Int) async -> [[String: Any]] {
        guard let result = try? await request("/admin/users/\(userId)/events") as? [String: Any] else { return [] }
        return (result["items"] as? [[String: Any]]) ?? []
    }

    func adminDeleteUser(userId: Int) async -> Bool {
        (try? await request("/admin/users/\(userId)", method: "DELETE")) != nil
    }

    func adminListAdmins() async throws -> [[String: Any]] {
        guard let result = try await request("/admin/admins") as? [String: Any] else { return [] }
        return (result["items"] as? [[String: Any]]) ?? []
    }

    func adminCreateAdmin(username: String, password: String) async throws {
        _ = try await post("/admin/admins", body: ["username": username, "password": password])
    }

    func adminResetPassword(adminId: Int, password: String) async throws {
        _ = try await put("/admin/admins/\(adminId)/password", body: ["password": password])
    }

    func adminDeleteAdmin(adminId: Int) async throws {
        _ = try await request("/admin/admins/\(adminId)", method: "DELETE")
    }
}
