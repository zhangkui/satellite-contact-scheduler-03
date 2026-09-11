// jsdom 缺少的浏览器 API 打桩（Element Plus 部分组件依赖）
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
;(globalThis as any).ResizeObserver = (globalThis as any).ResizeObserver ?? ResizeObserverStub

if (!window.matchMedia) {
  ;(window as any).matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  })
}
