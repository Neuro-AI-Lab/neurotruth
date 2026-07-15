import axios from "axios";

const REFRESH_KEY = "neurotruth.admin.refresh";
let accessToken = "";
let refreshPromise = null;
let authFailureHandler = null;

export const getStoredRefreshToken = () => sessionStorage.getItem(REFRESH_KEY) || "";
export const storeRefreshToken = (token) => sessionStorage.setItem(REFRESH_KEY, token);
export const setAccessToken = (token) => { accessToken = token || ""; };
export const setAuthFailureHandler = (handler) => { authFailureHandler = handler; };
export const clearSession = () => { accessToken = ""; sessionStorage.removeItem(REFRESH_KEY); };

const api = axios.create({ headers: { "Content-Type": "application/json" } });

api.interceptors.request.use((config) => {
  if (accessToken) config.headers.Authorization = `Bearer ${accessToken}`;
  return config;
});

api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config;
    const refreshToken = getStoredRefreshToken();
    const isAuthEndpoint = original?.url?.startsWith("/api/auth/");
    if (error.response?.status !== 401 || original?._retried || original?.skipAuthRefresh || isAuthEndpoint || !refreshToken) {
      if (error.response?.status === 401 && !isAuthEndpoint) { clearSession(); authFailureHandler?.(); }
      return Promise.reject(error);
    }
    original._retried = true;
    try {
      if (!refreshPromise) {
        refreshPromise = axios.post("/api/auth/refresh", { refreshToken }).then((response) => {
          const nextAccess = response.data?.accessToken || response.data?.access_token;
          const nextRefresh = response.data?.refreshToken || response.data?.refresh_token;
          if (!nextAccess) throw new Error("Missing access token");
          setAccessToken(nextAccess);
          if (nextRefresh) storeRefreshToken(nextRefresh);
          return nextAccess;
        }).finally(() => { refreshPromise = null; });
      }
      const nextAccess = await refreshPromise;
      original.headers = { ...original.headers, Authorization: `Bearer ${nextAccess}` };
      return api(original);
    } catch (refreshError) {
      clearSession(); authFailureHandler?.();
      return Promise.reject(refreshError);
    }
  },
);

export default api;
