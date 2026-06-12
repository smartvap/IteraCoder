import axios from "axios"
import { BASE_URL, HEADER } from "./config"
import { handleAuthError } from "@/api/authUtils"

const service = axios.create({
  baseURL: BASE_URL,
  withCredentials: false,
  headers: HEADER,
})

// 请求拦截器 - 自动携带 Token
service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem("token")
    if (token !== null) {
      config.headers.Authorization = "Bearer " + token
    }
    return config
  },
  (error) => {
    console.log(error)
    return Promise.reject(error)
  }
)

// 响应拦截器 - 统一错误处理 + 401 拦截
service.interceptors.response.use(
  (res: any) => {
    return res.data
  },
  (error) => {
    if (error.response && error.response.status === 401) {
      handleAuthError()
    }
    console.log(error)
    return Promise.reject(error)
  }
)

export default service
