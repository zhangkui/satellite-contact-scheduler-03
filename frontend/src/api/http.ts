import axios, { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一响应 R<T>：{ code, message, data }。
 * 冲突场景后端返回 HTTP 409，error.response.data.data.conflicts 携带冲突对象与时间段。
 */
export interface ApiError {
  status: number
  code: number
  message: string
  data: any
  conflicts?: any[]
}

const http = axios.create({
  baseURL: '/',
  timeout: 15000
})

http.interceptors.response.use(
  resp => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      const err: ApiError = {
        status: resp.status,
        code: body.code,
        message: body.message,
        data: body.data,
        conflicts: body.data?.conflicts
      }
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(err)
    }
    return body
  },
  (error: AxiosError<any>) => {
    const body = error.response?.data
    const apiError: ApiError = {
      status: error.response?.status ?? 0,
      code: body?.code ?? error.response?.status ?? 0,
      message: body?.message || error.message || '网络错误',
      data: body?.data,
      conflicts: body?.data?.conflicts
    }
    // 409 冲突为业务流程的一部分（工作台需要拿到冲突对象），调用方自行处理，不弹默认提示
    if (error.response?.status !== 409) {
      ElMessage.error(apiError.message)
    }
    return Promise.reject(apiError)
  }
)

export default http
