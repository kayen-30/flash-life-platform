// let commonURL = "http://192.168.50.115:8081";
let commonURL = "/api";

axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 10000;

axios.interceptors.request.use(
  config => {
    const token = sessionStorage.getItem("token");
    if (token) {
      config.headers["authorization"] = token;
    }
    return config;
  },
  error => Promise.reject(error)
);

axios.interceptors.response.use(
  response => {
    if (!response.data.success) {
      return Promise.reject(response.data.errorMsg);
    }
    return response.data;
  },
  error => {
    console.log(error);
    if (error.response && error.response.status === 401) {
      setTimeout(() => {
        location.href = "/login.html";
      }, 200);
      return Promise.reject("请先登录");
    }
    if (error.code === "ECONNABORTED") {
      return Promise.reject("请求超时，请稍后重试");
    }
    if (!error.response) {
      return Promise.reject("网络异常，请检查服务是否启动");
    }
    return Promise.reject(
      (error.response.data && error.response.data.errorMsg) || "服务器异常"
    );
  }
);

axios.defaults.paramsSerializer = function(params) {
  let p = "";
  Object.keys(params).forEach(k => {
    if (params[k]) {
      p = p + "&" + k + "=" + params[k];
    }
  });
  return p;
};

const util = {
  commonURL,
  getUrlParam(name) {
    let reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    let r = window.location.search.substr(1).match(reg);
    if (r != null) {
      return decodeURI(r[2]);
    }
    return "";
  },
  formatPrice(val) {
    if (typeof val === "string") {
      if (isNaN(val)) {
        return null;
      }
      const index = val.lastIndexOf(".");
      let p = "";
      if (index < 0) {
        p = val + "00";
      } else if (index === p.length - 2) {
        p = val.replace(".", "") + "0";
      } else {
        p = val.replace(".", "");
      }
      return parseInt(p);
    } else if (typeof val === "number") {
      if (!val) {
        return null;
      }
      const s = val + "";
      if (s.length === 0) {
        return "0.00";
      }
      if (s.length === 1) {
        return "0.0" + val;
      }
      if (s.length === 2) {
        return "0." + val;
      }
      const i = s.indexOf(".");
      if (i < 0) {
        return s.substring(0, s.length - 2) + "." + s.substring(s.length - 2);
      }
      const num = s.substring(0, i) + s.substring(i + 1);
      if (i === 1) {
        return "0.0" + num;
      }
      if (i === 2) {
        return "0." + num;
      }
      if (i > 2) {
        return num.substring(0, i - 2) + "." + num.substring(i - 2);
      }
    }
  }
};
