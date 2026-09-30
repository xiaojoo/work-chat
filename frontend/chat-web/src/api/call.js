import api from './index'

/**
 * 话单：站在这一个登录者角度看这一通算什么（ENDED / NO_ANSWER / MISSED / REJECTED /
 * CANCELLED / DISCONNECTED），不是通话本身的状态 —— 同一个人是主叫还是被叫，读出来不一样。
 * 服务端返回的是裸数组，没有 {code,data} 那层壳。
 */
export function getCallRecords(limit = 50) {
  return api.get('/call/records', { params: { limit } })
}
