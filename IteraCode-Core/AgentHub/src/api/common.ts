/**
 * 后端 API 常量枚举
 */

export const enum AgentApi {
  Decompose = "/agent/decompose",
  Confirm = "/agent/confirm",
  Result = "/agent/result",
}

export const enum AuthApi {
  Login = "/user/login",
  Register = "/user/register",
  Password = "/user/updatePassword",
  UserInfo = "/user/{id}",
}

export const enum ChatApi {
  Stream = "/chat/stream",
  Send = "/chat/send",
  Models = "/chat/models",
  SimpleChat = "/chat/simple",
  StreamChat = "/chat/stream",
}

export const enum RagApi {
  StreamRag = "/ai/rag",
}

export const enum AdminApi {
  Models = "/admin/models",
}

export const enum OneApi {
  AddOneApi = "/one-api",
  QueryApi = "/select",
  ChangeApi = "/change/",
  QueryOneApi = "/select/",
  DeleteOneApi = "/delete/",
  DeleteApi = "/delete",
}

export const enum DrawApi {
  DrawApi = "/draw/",
}

export const enum LogInfoApi {
  QueryPage = "/log/page",
  BatchDelete = "/log/batch",
}

export const enum UserApi {
  QueryPage = "/user/page",
  Download = "/user/download",
  Register = "/user/register",
  Update = "/user/update",
  Login = "/user/login",
  UpdatePassword = "/user/updatePassword",
}

export const enum WorkflowApi {
  Route  = "/chat2/route",
  Start  = "/workflow/start",
  State  = "/workflow/state",
  Resume = "/workflow/resume",
}

export const enum ProjectApi {
  Save   = "/project/save",
  Deploy = "/project/deploy",
  DockerCheck = "/project/docker-check",
}
