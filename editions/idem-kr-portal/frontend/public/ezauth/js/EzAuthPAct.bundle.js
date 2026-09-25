var __webpack_modules__ = {
    "./src/ezauth/js/EzauthAlert.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => __WEBPACK_DEFAULT_EXPORT__
        });
        var _utils_Logger_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./src/ezauth/utils/Logger.js");
        var EzauthAlert = {};
        (function(alert) {
            alert.isInitialized = false;
            alert.title = "안 내";
            alert.message = "";
            alert.confirmButtonTitle = "확인";
            alert.closeButtonTitle = "닫기";
            alert.zIndex = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig.alertZIndex : 9999;
            alert.option = {};
            alert.onConfirmCallback = null;
            alert.onCancelCallback = null;
            alert.handleConfirmClick = function(event) {
                if (event.type === "keyup" && event.which !== 13 && event.which !== 32) {
                    return;
                }
                var callbackToExecute = alert.onConfirmCallback;
                alert.close();
                if (callbackToExecute !== null) {
                    callbackToExecute();
                }
            };
            alert.handleCloseClick = function(event) {
                if (event.type === "keyup" && event.which !== 13 && event.which !== 32) {
                    return;
                }
                var callbackToExecute = alert.onCancelCallback;
                alert.close();
                if (callbackToExecute !== null) {
                    callbackToExecute();
                }
            };
            alert.init = function() {
                if (alert.isInitialized === true) {
                    return;
                }
                alert.isInitialized = true;
            };
            alert.close = function() {
                var confirmButton = document.querySelector("#EzauthAlert .button.confirm");
                if (confirmButton) {
                    confirmButton.removeEventListener("click", alert.handleConfirmClick);
                    confirmButton.removeEventListener("keyup", alert.handleConfirmClick);
                }
                var alertElement = document.getElementById("EzauthAlert");
                if (alertElement) {
                    alertElement.remove();
                }
                alert.isInitialized = false;
                alert.onConfirmCallback = null;
                alert.onCancelCallback = null;
                var _containerElement = document.querySelector("#EzauthContainer");
                _containerElement.removeAttribute("inert");
                if (alert.option) {
                    if (alert.option.deviceType && alert.option.deviceType === "mobile") {
                        if (alert.option.state && alert.option.state === "selectPolicy") {
                            var reqAuthButton = document.querySelector("#EzauthContainer > div > div > section.step1 > section.buttons > div.button.req-auth");
                            if (reqAuthButton) {
                                reqAuthButton.focus();
                            }
                        }
                    }
                }
            };
            alert.registEvent = function() {};
            alert.setFocusTrap = function() {
                var modalElem = document.getElementById("EzauthAlert");
                if (!modalElem) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_0__["default"].error("ID 'EzauthAlert'를 가진 요소를 찾을 수 없습니다.");
                    return;
                }
                var FOCUSABLE_SELECTOR = '\n        a[href],\n        button:not([disabled]),\n        input:not([disabled]),\n        select:not([disabled]),\n        textarea:not([disabled]),\n        [tabindex]:not([tabindex="-1"])\n    ';
                var focusableNodes = modalElem.querySelectorAll(FOCUSABLE_SELECTOR);
                var focusableElements = [];
                for (var i = 0; i < focusableNodes.length; i++) {
                    focusableElements.push(focusableNodes[i]);
                }
                if (focusableElements.length === 0) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_0__["default"].warn("영역 내에 포커스 가능한 요소가 없습니다.");
                    return;
                }
                var firstFocusableElement = focusableElements[0];
                var lastFocusableElement = focusableElements[focusableElements.length - 1];
                modalElem.addEventListener("keydown", function(e) {
                    var isTabPressed = e.key === "Tab" || e.keyCode === 9;
                    if (!isTabPressed) {
                        return;
                    }
                    if (e.shiftKey) {
                        if (document.activeElement === firstFocusableElement) {
                            lastFocusableElement.focus();
                            e.preventDefault();
                        }
                    } else {
                        if (document.activeElement === lastFocusableElement) {
                            firstFocusableElement.focus();
                            e.preventDefault();
                        }
                    }
                });
            };
            alert.show = function(message, title, confirmButtonTitle, zIndex, option, onConfirm, onCancel) {
                alert.onConfirmCallback = null;
                alert.onCancelCallback = null;
                alert.init();
                var isConfirmAlert = option === "confirm";
                var isCloseAlert = option === "close";
                alert.message = message;
                if (title != null) {
                    alert.title = title;
                }
                if (confirmButtonTitle != null) {
                    alert.confirmButtonTitle = confirmButtonTitle;
                }
                if (zIndex != null) {
                    alert.zIndex = zIndex;
                }
                if (option != null) {
                    alert.option = option;
                }
                if (onConfirm != null) {
                    alert.onConfirmCallback = onConfirm;
                }
                if (onCancel != null) {
                    alert.onCancelCallback = onCancel;
                }
                var existingAlert = document.getElementById("EzauthAlert");
                if (existingAlert) {
                    existingAlert.remove();
                }
                var buttons = isConfirmAlert ? '\n                <div role="button" tabindex="0" class="button cancel">취소</div>\n                <div role="button" tabindex="0" class="button confirm">\n                    '.concat(isCloseAlert ? alert.closeButtonTitle : alert.confirmButtonTitle, "\n                </div>\n            ") : '\n                <div role="button" tabindex="0" class="button confirm">\n                    '.concat(isCloseAlert ? alert.closeButtonTitle : alert.confirmButtonTitle, "\n                </div>\n            ");
                var alertDomStr = '\n            <div id="EzauthAlert">\n                <div class="encase">\n                    <div class="Alert-Wrap">\n                        <div class="header">\n                            <p class="header_title" tabindex="0">'.concat(alert.title, '</p>\n                            <button type="button" class="button close" aria-label="닫기">\n                                <img src="assets/img/icon_close_blk.png" title="닫기" alt="닫기">\n                            </button>\n                        </div>\n                        <div class="alert">\n                            <div class="text" tabindex="0">').concat(alert.message, '</div>\n                        </div>\n                        <section class="buttons">\n                            ').concat(buttons, "\n                        </section>\n                    </div>\n                </div>\n            </div>\n        ");
                var tempDiv = document.createElement("div");
                tempDiv.innerHTML = alertDomStr.trim();
                var alertDomObj = tempDiv.firstChild;
                alertDomObj.style.zIndex = alert.zIndex;
                document.body.appendChild(alertDomObj);
                alert.setFocusTrap();
                var confirmButton = document.querySelector("#EzauthAlert .button.confirm");
                var cancelButton = document.querySelector("#EzauthAlert .button.cancel");
                var closeButton = document.querySelector("#EzauthAlert .button.close");
                if (isConfirmAlert) {
                    if (confirmButton) {
                        confirmButton.addEventListener("click", alert.handleConfirmClick);
                        confirmButton.addEventListener("keyup", alert.handleConfirmClick);
                    }
                    if (cancelButton) {
                        cancelButton.addEventListener("click", alert.handleCloseClick);
                        cancelButton.addEventListener("keyup", alert.handleCloseClick);
                    }
                    if (closeButton) {
                        closeButton.addEventListener("click", alert.handleCloseClick);
                        closeButton.addEventListener("keyup", alert.handleCloseClick);
                    }
                } else {
                    if (confirmButton) {
                        confirmButton.addEventListener("click", alert.handleConfirmClick);
                        confirmButton.addEventListener("keyup", alert.handleConfirmClick);
                    }
                    if (closeButton) {
                        closeButton.addEventListener("click", alert.handleConfirmClick);
                        closeButton.addEventListener("keyup", alert.handleConfirmClick);
                    }
                }
                var _targetElements = document.querySelector("#EzauthAlert .header .header_title");
                _targetElements.focus();
                var _containerElement = document.querySelector("#EzauthContainer");
                _containerElement.setAttribute("inert", "true");
            };
        })(EzauthAlert);
        const __WEBPACK_DEFAULT_EXPORT__ = EzauthAlert;
    },
    "./src/ezauth/js/EzauthBlock.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => __WEBPACK_DEFAULT_EXPORT__
        });
        var _EzauthCore__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./src/ezauth/js/EzauthCore.js");
        var EzauthBlock = {};
        (function(block) {
            block.isInitialized = false;
            block.zIndex = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig.blockZIndex : 9998;
            block.option = {};
            block.callback = null;
            block.handleTabTrap = function(event) {
                if (event.key === "Tab" || event.keyCode === 9 || event.key === "Enter" || event.keyCode === 13 || event.key === " " || event.keyCode === 32) {
                    event.preventDefault();
                    event.stopPropagation();
                }
            };
            block.handleConfirmClick = function(event) {
                if (event.type === "keyup" && event.which !== 13 && event.which !== 32) {
                    return;
                }
                block.close();
                if (block.callback !== null) {
                    block.callback();
                }
            };
            block.init = function() {
                if (block.isInitialized === true) {
                    return;
                }
                block.isInitialized = true;
            };
            block.close = function() {
                var confirmButton = document.querySelector("#EzauthBlock .button.confirm");
                if (confirmButton) {
                    confirmButton.removeEventListener("click", block.handleConfirmClick);
                    confirmButton.removeEventListener("keyup", block.handleConfirmClick);
                }
                var blockElement = document.getElementById("EzauthBlock");
                if (blockElement) {
                    blockElement.remove();
                }
                document.removeEventListener("keydown", block.handleTabTrap, true);
                document.removeEventListener("keyup", block.handleTabTrap, true);
                block.isInitialized = false;
            };
            block.registEvent = function() {};
            block.show = function(zIndex, option, callback) {
                block.init();
                if (zIndex != null) {
                    block.zIndex = zIndex;
                }
                if (option != null) {
                    block.option = option;
                }
                if (callback != null) {
                    block.callback = callback;
                }
                var existingBlock = document.getElementById("EzauthBlock");
                if (existingBlock) {
                    existingBlock.remove();
                }
                var ezauthRootPath = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_0__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_0__["default"].basicInfo ? _EzauthCore__WEBPACK_IMPORTED_MODULE_0__["default"].basicInfo.ezauthRootPath : "";
                var blockDomStr = '\n            <div id="EzauthBlock">\n                <div class="encase">\n                    <div class="Block-Wrap">\n                        <section class="block">\n                            <img class="loading-img" src="'.concat(ezauthRootPath, '/assets/img/ico_loading.svg" style="margin: 0 auto; padding: 0;" title="" alt="로딩 중">\n                        </section>\n                    </div>\n                </div>\n            </div>\n        ');
                var tempDiv = document.createElement("div");
                tempDiv.innerHTML = blockDomStr.trim();
                var blockDomObj = tempDiv.firstChild;
                blockDomObj.style.zIndex = block.zIndex;
                document.body.appendChild(blockDomObj);
                document.addEventListener("keydown", block.handleTabTrap, true);
                document.addEventListener("keyup", block.handleTabTrap, true);
                var confirmButton = document.querySelector("#EzauthBlock .button.confirm");
                if (confirmButton) {
                    confirmButton.addEventListener("click", block.handleConfirmClick);
                    confirmButton.addEventListener("keyup", block.handleConfirmClick);
                }
            };
        })(EzauthBlock);
        const __WEBPACK_DEFAULT_EXPORT__ = EzauthBlock;
    },
    "./src/ezauth/js/EzauthCore.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => __WEBPACK_DEFAULT_EXPORT__
        });
        var _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/slicedToArray.js");
        var _babel_runtime_helpers_defineProperty__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/defineProperty.js");
        var _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/typeof.js");
        var _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__("./src/ezauth/js/EzauthAlert.js");
        var _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__("./src/ezauth/js/EzauthErrorHandler.js");
        var _EzauthBlock__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__("./src/ezauth/js/EzauthBlock.js");
        var _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__("./src/ezauth/utils/Logger.js");
        function _createForOfIteratorHelper(r, e) {
            var t = "undefined" != typeof Symbol && r[Symbol.iterator] || r["@@iterator"];
            if (!t) {
                if (Array.isArray(r) || (t = _unsupportedIterableToArray(r)) || e && r && "number" == typeof r.length) {
                    t && (r = t);
                    var _n = 0, F = function F() {};
                    return {
                        s: F,
                        n: function n() {
                            return _n >= r.length ? {
                                done: !0
                            } : {
                                done: !1,
                                value: r[_n++]
                            };
                        },
                        e: function e(r) {
                            throw r;
                        },
                        f: F
                    };
                }
                throw new TypeError("Invalid attempt to iterate non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.");
            }
            var o, a = !0, u = !1;
            return {
                s: function s() {
                    t = t.call(r);
                },
                n: function n() {
                    var r = t.next();
                    return a = r.done, r;
                },
                e: function e(r) {
                    u = !0, o = r;
                },
                f: function f() {
                    try {
                        a || null == t["return"] || t["return"]();
                    } finally {
                        if (u) throw o;
                    }
                }
            };
        }
        function _unsupportedIterableToArray(r, a) {
            if (r) {
                if ("string" == typeof r) return _arrayLikeToArray(r, a);
                var t = {}.toString.call(r).slice(8, -1);
                return "Object" === t && r.constructor && (t = r.constructor.name), "Map" === t || "Set" === t ? Array.from(r) : "Arguments" === t || /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(t) ? _arrayLikeToArray(r, a) : void 0;
            }
        }
        function _arrayLikeToArray(r, a) {
            (null == a || a > r.length) && (a = r.length);
            for (var e = 0, n = Array(a); e < a; e++) n[e] = r[e];
            return n;
        }
        function ownKeys(e, r) {
            var t = Object.keys(e);
            if (Object.getOwnPropertySymbols) {
                var o = Object.getOwnPropertySymbols(e);
                r && (o = o.filter(function(r) {
                    return Object.getOwnPropertyDescriptor(e, r).enumerable;
                })), t.push.apply(t, o);
            }
            return t;
        }
        function _objectSpread(e) {
            for (var r = 1; r < arguments.length; r++) {
                var t = null != arguments[r] ? arguments[r] : {};
                r % 2 ? ownKeys(Object(t), !0).forEach(function(r) {
                    (0, _babel_runtime_helpers_defineProperty__WEBPACK_IMPORTED_MODULE_1__["default"])(e, r, t[r]);
                }) : Object.getOwnPropertyDescriptors ? Object.defineProperties(e, Object.getOwnPropertyDescriptors(t)) : ownKeys(Object(t)).forEach(function(r) {
                    Object.defineProperty(e, r, Object.getOwnPropertyDescriptor(t, r));
                });
            }
            return e;
        }
        var EzauthCore = {};
        (function(core) {
            core.basicInfo = {};
            core.setParam = function(value) {
                if (!value || (0, _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_2__["default"])(value) !== "object") {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].error("Invalid parameter in setParam");
                    return;
                }
                core.basicInfo = value;
                var agent = window.navigator.userAgent.toLowerCase();
                if (core.isEmpty(core.basicInfo.appToAppUse)) {
                    core.basicInfo.appToAppUse = false;
                }
                if (core.isEmpty(core.basicInfo.deviceType)) {
                    if (typeof window.parent.EzauthConfig !== "undefined" && window.parent.EzauthConfig.DEVICE_TYPE) {
                        if (agent.indexOf("mobile") > -1) {
                            core.basicInfo.deviceType = window.parent.EzauthConfig.DEVICE_TYPE.MOBILE_WEB;
                            if (core.basicInfo.appToAppUse) {
                                core.basicInfo.deviceType = window.parent.EzauthConfig.DEVICE_TYPE.MOBILE_APP;
                            }
                        } else {
                            core.basicInfo.deviceType = window.parent.EzauthConfig.DEVICE_TYPE.PC;
                        }
                    } else {
                        _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].warn("window.parent.EzauthConfig or window.parent.EzauthConfig.DEVICE_TYPE is not defined.");
                        core.basicInfo.deviceType = "unknown";
                    }
                }
                if (core.isEmpty(core.basicInfo.browserType)) {
                    if (typeof window.parent.EzauthConfig !== "undefined" && window.parent.EzauthConfig.BROWSER_TYPE) {
                        switch (true) {
                          case agent.indexOf("edge") > -1:
                            core.basicInfo.browserType = window.parent.EzauthConfig.BROWSER_TYPE.Edge;
                            break;

                          case agent.indexOf("edg/") > -1:
                            core.basicInfo.browserType = window.parent.EzauthConfig.BROWSER_TYPE.Edge;
                            break;

                          case agent.indexOf("opr") > -1 && !!window.opr:
                            core.basicInfo.browserType = window.parent.EzauthConfig.BROWSER_TYPE.Opera;
                            break;

                          case agent.indexOf("chrome") > -1 && !!window.chrome:
                            core.basicInfo.browserType = window.parent.EzauthConfig.BROWSER_TYPE.Chrome;
                            break;

                          case agent.indexOf("trident") > -1:
                            core.basicInfo.browserType = window.parent.EzauthConfig.BROWSER_TYPE.IE;
                            break;

                          case agent.indexOf("firefox") > -1:
                            core.basicInfo.browserType = window.parent.EzauthConfig.BROWSER_TYPE.Firefox;
                            break;

                          case agent.indexOf("safari") > -1:
                            core.basicInfo.browserType = window.parent.EzauthConfig.BROWSER_TYPE.Safari;
                            break;

                          default:
                            core.basicInfo.browserType = window.parent.EzauthConfig.BROWSER_TYPE.Etc;
                            break;
                        }
                    } else {
                        _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].warn("window.parent.EzauthConfig or window.parent.EzauthConfig.BROWSER_TYPE is not defined.");
                        core.basicInfo.browserType = "unknown";
                    }
                }
                if (typeof window.parent.EzauthConfig !== "undefined") {
                    if (core.basicInfo.deviceType.indexOf("Mobile") > -1) {
                        if (agent.indexOf("android") > -1) {
                            core.basicInfo.os = window.parent.EzauthConfig.MOBILE_OS.ANDROID;
                        } else if (agent.indexOf("iphone") > -1 || agent.indexOf("ipad") > -1 || agent.indexOf("ipod") > -1) {
                            core.basicInfo.os = window.parent.EzauthConfig.MOBILE_OS.IOS;
                        } else {
                            core.basicInfo.os = "unknown";
                        }
                    } else if (core.basicInfo.deviceType.indexOf("PC") > -1) {
                        if (agent.indexOf("win") > -1) {
                            core.basicInfo.os = window.parent.EzauthConfig.PC_OS.WINDOWS;
                        } else if (agent.indexOf("mac") > -1) {
                            core.basicInfo.os = window.parent.EzauthConfig.PC_OS.MAC;
                        } else {
                            core.basicInfo.os = "unknown";
                        }
                    } else {
                        _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].warn("window.parent.EzauthConfig or window.parent.EzauthConfig.PC_OS is not defined.");
                        core.basicInfo.os = "unknown";
                    }
                }
            };
            core.getParam = function() {
                return core.basicInfo;
            };
            core.getInstallPath = function() {
                var param = location.search.split(/[?&]/);
                var value = "";
                for (var i = 0; i < param.length; i++) {
                    var pair = param[i].split("=");
                    if (pair[0] == name) {
                        value = pair[1];
                        break;
                    }
                }
                return value;
            };
            core.ezauthJsonConf = {};
            core.jsonUiConf = {};
            core.authReqResponse = {};
            core.authResultResponse = {};
            core.authCheckResponse = {};
            core.jsonSelectedProvider = {};
            core.messageToParent = {
                errno: 1,
                errstr: "",
                resultCode: -1,
                resultMsg: "",
                data: {}
            };
            core.sendMessageToParent = function(errorHandler, data) {
                core.messageToParent.errno = errorHandler.errno;
                core.messageToParent.errstr = errorHandler.errstr;
                core.messageToParent.resultCode = errorHandler.resultCode;
                core.messageToParent.resultMsg = errorHandler.resultMsg;
                if (data != null) {
                    core.messageToParent.data = data;
                }
                window.parent.postMessage(JSON.stringify(core.messageToParent), "*");
            };
            core.sendRequest = function(options) {
                var xhr = new XMLHttpRequest;
                xhr.open(options.method || "GET", options.url, true);
                if (options.headers) {
                    for (var key in options.headers) {
                        if (options.headers.hasOwnProperty(key)) {
                            xhr.setRequestHeader(key, options.headers[key]);
                        }
                    }
                }
                xhr.timeout = options.timeout || window.parent.EzauthConfig.ajaxTimeout;
                xhr.onload = function() {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        try {
                            var data = JSON.parse(xhr.responseText);
                            if (options.onSuccess) {
                                options.onSuccess(data);
                            }
                        } catch (e) {
                            _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].error("JSON parsing error:", e);
                            if (options.onError) {
                                options.onError(e);
                            }
                        }
                    } else {
                        if (options.onError) {
                            options.onError(new Error("HTTP status: " + xhr.status));
                        }
                    }
                };
                xhr.onloadend = function() {
                    if (options.onComplete) {
                        options.onComplete();
                    }
                };
                xhr.onerror = function() {
                    if (options.onError) {
                        options.onError(new Error("Network error"));
                    }
                };
                xhr.ontimeout = function() {
                    if (options.onError) {
                        options.onError(new Error("Request timeout"));
                    }
                };
                xhr.send(options.data ? JSON.stringify(options.data) : undefined);
            };
            core.isEmpty = function(value) {
                return value == null || value === "undefined" || value === "" || (value === null || value === void 0 ? void 0 : value.length) !== undefined && value.length === 0;
            };
            core.getUserInfoElement = function(fieldType) {
                var BASE = "#EzauthContainer .body .step1 .user-info ";
                var selectors = {
                    name: "li.name input",
                    birth: "li.birth input",
                    phone: "li.hp input.telnum_end",
                    phonePrefix: "li.hp input.sel_telnum",
                    biz: "li.biz-registration-number input",
                    ssn1: "li.ssn .ssn1",
                    ssn2: "li.ssn .ssn2",
                    telco: "li.hp .telco"
                };
                return document.querySelector(BASE + selectors[fieldType]);
            };
            core.getApiUrl = function(apiType) {
                var apiPaths = {
                    token: core.ezauthJsonConf.url.api.tokenPath,
                    txid: core.ezauthJsonConf.url.api.txidReqPath,
                    request: core.ezauthJsonConf.url.api.requestPath,
                    result: core.ezauthJsonConf.url.api.resultPath,
                    authCheck: core.ezauthJsonConf.url.api.authCheck,
                    endecrypt: core.ezauthJsonConf.url.api.endecryptReq
                };
                var server = apiType === "authCheck" ? core.ezauthJsonConf.url.authCheckServer : core.ezauthJsonConf.url.server;
                return server.baseUrl + server.contextPath + apiPaths[apiType];
            };
            core.safeGetError = function(errorKey) {
                var _EzauthErrorHandler$e, _EzauthErrorHandler$e2;
                return (_EzauthErrorHandler$e = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === null || _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === void 0 || (_EzauthErrorHandler$e2 = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error) === null || _EzauthErrorHandler$e2 === void 0 ? void 0 : _EzauthErrorHandler$e2[errorKey]) !== null && _EzauthErrorHandler$e !== void 0 ? _EzauthErrorHandler$e : {
                    errno: -1,
                    errstr: "Error handler not defined."
                };
            };
            core.getServiceErrorHandler = function(callback) {
                return function(error) {
                    var errMsg = null;
                    if (typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined") {
                        if (core.ezauthJsonConf.service == "auth") {
                            errMsg = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_SIMPLEAUTH_ERROR.errstr;
                        } else if (core.ezauthJsonConf.service == "sign") {
                            errMsg = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_SIMPLESIGN_ERROR.errstr;
                        } else if (core.ezauthJsonConf.service == "authBiz") {
                            errMsg = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_SIMPLEAUTHBIZ_ERROR.errstr;
                        } else if (core.ezauthJsonConf.service == "signBiz") {
                            errMsg = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_SIMPLESIGNBIZ_ERROR.errstr;
                        }
                    } else {
                        _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].error("EzauthErrorHandler is not defined.");
                        errMsg = "An unknown error occurred.";
                    }
                    callback({
                        errstr: errMsg
                    });
                };
            };
            core.getEzauthJsonConf = function(getEzauthJsonConfCallback) {
                var _core$basicInfo$siteI;
                var url = core.basicInfo.ezauthRootPath + core.basicInfo.configPath;
                var siteId = "portal";
                if ((_core$basicInfo$siteI = core.basicInfo.siteInfo) !== null && _core$basicInfo$siteI !== void 0 && _core$basicInfo$siteI.siteId) {
                    siteId = core.basicInfo.siteInfo.siteId;
                }
                var defaults = {
                    service: "authBiz",
                    url: {
                        api: {
                            tokenPath: "site-ui-conf/v1",
                            requestPath: "auth-request/v1",
                            txidReqPath: "txidReq/v1",
                            resultPath: "auth-result/v1",
                            endecryptReq: "endecryptReq/v1"
                        }
                    },
                    siteInfo: {
                        siteId,
                        title: "간편인증",
                        siteImgUrl: "assets/img/logo.png"
                    },
                    policys: {
                        policyTitle: "이용 동의",
                        policy1: {
                            title: "서비스 이용약관 동의",
                            required: "Y",
                            hidden: false,
                            path: "site/" + siteId + "/policy1.html"
                        },
                        policy2: {
                            title: "개인정보 수집 및 이용 동의",
                            required: "Y",
                            hidden: false,
                            path: "site/" + siteId + "/policy2.html"
                        },
                        policy3: {
                            title: "개인정보 제3자 제공 동의",
                            required: "Y",
                            hidden: false,
                            path: "site/" + siteId + "/policy3.html"
                        }
                    },
                    signInfo: {
                        signPKCSType: "PKCS7",
                        signType: "plainText",
                        signTitle: "간편인증(개인사업자)",
                        signContents: "",
                        signDocEncType: "UTF-8",
                        signDocHashType: "SHA-256",
                        signContentInclude: true
                    },
                    pollingInterval: "1",
                    pollingCnt: "60",
                    qrTimer: "600",
                    usePolling: false,
                    businessInfoReq: "1"
                };
                core.sendRequest({
                    method: "GET",
                    url,
                    onSuccess: function onSuccess(data) {
                        var _mergedConfig$signInf;
                        data.apiType = "cloud";
                        var mergedConfig = JSON.parse(JSON.stringify(defaults));
                        if (data.url && data.url.api) {
                            if (!mergedConfig.url) mergedConfig.url = {};
                            if (!mergedConfig.url.api) mergedConfig.url.api = {};
                            Object.assign(mergedConfig.url.api, data.url.api);
                        }
                        if (data.url) {
                            for (var key in data.url) {
                                if (key !== "api") {
                                    if (!mergedConfig.url) mergedConfig.url = {};
                                    mergedConfig.url[key] = data.url[key];
                                }
                            }
                        }
                        for (var key in data) {
                            if (key !== "url") {
                                mergedConfig[key] = data[key];
                            }
                        }
                        if (core.basicInfo.service) mergedConfig.service = core.basicInfo.service;
                        if (core.basicInfo.url && core.basicInfo.url.api) {
                            var apiKeys = [ "tokenPath", "requestPath", "txidReqPath", "resultPath", "endecryptReq" ];
                            apiKeys.forEach(function(key) {
                                if (core.basicInfo.url.api[key]) {
                                    mergedConfig.url.api[key] = core.basicInfo.url.api[key];
                                }
                            });
                        }
                        if (core.basicInfo.siteInfo) {
                            Object.assign(mergedConfig.siteInfo, core.basicInfo.siteInfo);
                        }
                        if (core.basicInfo.policys) {
                            mergedConfig.policys = core.basicInfo.policys;
                        }
                        if (core.basicInfo.signInfo) {
                            Object.assign(mergedConfig.signInfo, core.basicInfo.signInfo);
                        }
                        var signContents = (_mergedConfig$signInf = mergedConfig.signInfo) === null || _mergedConfig$signInf === void 0 ? void 0 : _mergedConfig$signInf.signContents;
                        if (!Array.isArray(signContents)) {
                            mergedConfig.signInfo.signContents = [ signContents ];
                        }
                        if (core.basicInfo.pollingInterval) mergedConfig.pollingInterval = core.basicInfo.pollingInterval;
                        if (core.basicInfo.pollingCnt) mergedConfig.pollingCnt = core.basicInfo.pollingCnt;
                        if (core.basicInfo.qrTimer) mergedConfig.qrTimer = core.basicInfo.qrTimer;
                        if (typeof core.basicInfo.usePolling !== "undefined") mergedConfig.usePolling = core.basicInfo.usePolling;
                        if (core.basicInfo.businessInfoReq) mergedConfig.businessInfoReq = core.basicInfo.businessInfoReq;
                        core.ezauthJsonConf = mergedConfig;
                        if (core.basicInfo.serviceType === "sign" || core.basicInfo.serviceType === "signBiz") {
                            core.ezauthJsonConf.service = "signBiz";
                            if (core.ezauthJsonConf.siteInfo) {
                                core.ezauthJsonConf.siteInfo.title = "간편전자서명";
                            }
                            if (core.ezauthJsonConf.signInfo) {
                                core.ezauthJsonConf.signInfo.signTitle = "전자서명(개인사업자)";
                            }
                        }
                        getEzauthJsonConfCallback(typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined" ? {
                            errno: 0,
                            errstr: ""
                        } : {
                            errno: -1,
                            errstr: "Error handler not defined."
                        });
                    },
                    onError: function onError(error) {
                        getEzauthJsonConfCallback(core.safeGetError("API_FILE_NOT_FOUND_ERROR"));
                    }
                });
            };
            core.getUiConf = function(getUiConfCallback) {
                var url = core.getApiUrl("token");
                core.sendRequest({
                    method: "POST",
                    url,
                    headers: {
                        "Content-Type": "application/json; charset=utf-8"
                    },
                    data: {
                        siteInfo: core.ezauthJsonConf.siteInfo
                    },
                    onSuccess: function onSuccess(data) {
                        if (data.resultCode == "2000") {
                            core.jsonUiConf = data;
                            getUiConfCallback({
                                errno: 0,
                                errstr: ""
                            });
                        } else if (data.resultCode == "3000") {
                            getUiConfCallback(core.safeGetError("API_INVALID_SITE_ID"));
                        } else {
                            getUiConfCallback(core.safeGetError("API_FAIL_TO_GET_UI_CONF"));
                        }
                    },
                    onError: function onError(error) {
                        getUiConfCallback(core.safeGetError("API_FAIL_TO_GET_UI_CONF"));
                    }
                });
            };
            core.getTxid = function(getTxidCallback) {
                var url = core.getApiUrl("txid");
                core.sendRequest({
                    method: "POST",
                    url,
                    headers: {
                        "Content-Type": "application/json; charset=utf-8"
                    },
                    data: {
                        siteInfo: core.ezauthJsonConf.siteInfo
                    },
                    onSuccess: function onSuccess(data) {
                        if (data.resultCode == "2000") {
                            core.jsonUiConf.txId = data.txId, core.jsonUiConf.tokenId = data.tokenId, core.jsonUiConf.userToken = data.userToken, 
                            getTxidCallback({
                                errno: 0,
                                errstr: ""
                            });
                        } else {
                            getTxidCallback(core.safeGetError("API_FAIL_TO_GET_TOKEN"));
                        }
                    },
                    onError: function onError(error) {
                        getTxidCallback(core.safeGetError("API_FAIL_TO_GET_TOKEN"));
                    }
                });
            };
            core.sendAuthRequest = function(sendAuthRequestCallback) {
                var _core$getUserInfoElem2, _core$getUserInfoElem5, _core$getUserInfoElem6, _core$getUserInfoElem7, _core$getUserInfoElem8, _core$ezauthJsonConf$, _core$ezauthJsonConf$2, _core$ezauthJsonConf$3, _core$ezauthJsonConf$4, _core$ezauthJsonConf$5, _core$ezauthJsonConf$6, _core$ezauthJsonConf$7;
                if (typeof _EzauthBlock__WEBPACK_IMPORTED_MODULE_5__["default"] !== "undefined") {
                    _EzauthBlock__WEBPACK_IMPORTED_MODULE_5__["default"].show();
                } else {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].warn("EzauthBlock is not defined.");
                }
                var policys = {};
                var policysObj = document.querySelectorAll("#EzauthContainer .body .step1 .policys li.policy");
                policysObj.forEach(function(policyObj, i) {
                    if (policyObj.style.display !== "none") {
                        var checkboxImg = policyObj.querySelector("img.checkbox");
                        policys["policy" + (i + 1)] = checkboxImg && checkboxImg.classList.contains("on") ? "Y" : "N";
                    }
                });
                var telcoTycd;
                if (core.jsonSelectedProvider.providerId == "passauth" || core.jsonSelectedProvider.providerId == "kb") {
                    var _core$getUserInfoElem;
                    telcoTycd = ((_core$getUserInfoElem = core.getUserInfoElement("telco")) === null || _core$getUserInfoElem === void 0 ? void 0 : _core$getUserInfoElem.value) || "";
                } else {
                    telcoTycd = "";
                }
                var requestType;
                if (core.basicInfo.requestType !== undefined && core.basicInfo.requestType !== null && core.basicInfo.requestType !== "") {
                    requestType = core.basicInfo.requestType;
                }
                var reqJson = {
                    siteInfo: core.ezauthJsonConf.siteInfo,
                    txId: core.jsonUiConf.txId,
                    tokenId: core.jsonUiConf.tokenId,
                    userToken: core.jsonUiConf.userToken,
                    deviceInfo: {
                        type: core.basicInfo.deviceType,
                        browser: core.basicInfo.browserType,
                        os: core.basicInfo.os
                    },
                    userInfo: {
                        name: core.base64EncodeUTF8((((_core$getUserInfoElem2 = core.getUserInfoElement("name")) === null || _core$getUserInfoElem2 === void 0 ? void 0 : _core$getUserInfoElem2.value) || "").replace(/ /g, "")),
                        phone: function(_core$getUserInfoElem3, _core$getUserInfoElem4) {
                            var telPrefix = ((_core$getUserInfoElem3 = core.getUserInfoElement("phonePrefix")) === null || _core$getUserInfoElem3 === void 0 ? void 0 : _core$getUserInfoElem3.value) || "";
                            var telRest = ((_core$getUserInfoElem4 = core.getUserInfoElement("phone")) === null || _core$getUserInfoElem4 === void 0 ? void 0 : _core$getUserInfoElem4.value) || "";
                            return core.base64EncodeUTF8((telPrefix + telRest).replace(/ /g, ""));
                        }(),
                        birthday: core.base64EncodeUTF8((((_core$getUserInfoElem5 = core.getUserInfoElement("birth")) === null || _core$getUserInfoElem5 === void 0 ? void 0 : _core$getUserInfoElem5.value) || "").replace(/ /g, "")),
                        businessNumber: core.base64EncodeUTF8((((_core$getUserInfoElem6 = core.getUserInfoElement("biz")) === null || _core$getUserInfoElem6 === void 0 ? void 0 : _core$getUserInfoElem6.value) || "").replace(/ /g, "")),
                        ssn1: core.base64EncodeUTF8((((_core$getUserInfoElem7 = core.getUserInfoElement("ssn1")) === null || _core$getUserInfoElem7 === void 0 ? void 0 : _core$getUserInfoElem7.value) || "").replace(/ /g, "")),
                        ssn2: core.base64EncodeUTF8((((_core$getUserInfoElem8 = core.getUserInfoElement("ssn2")) === null || _core$getUserInfoElem8 === void 0 ? void 0 : _core$getUserInfoElem8.value) || "").replace(/ /g, "")),
                        policys,
                        telcoTycd,
                        ci: ""
                    },
                    providerInfo: {
                        providerId: core.jsonSelectedProvider.providerId,
                        service: [ core.ezauthJsonConf.service ]
                    },
                    signInfo: {
                        signPKCSType: (_core$ezauthJsonConf$ = core.ezauthJsonConf.signInfo) === null || _core$ezauthJsonConf$ === void 0 ? void 0 : _core$ezauthJsonConf$.signPKCSType,
                        signType: (_core$ezauthJsonConf$2 = core.ezauthJsonConf.signInfo) === null || _core$ezauthJsonConf$2 === void 0 ? void 0 : _core$ezauthJsonConf$2.signType,
                        signTitle: (_core$ezauthJsonConf$3 = core.ezauthJsonConf.signInfo) === null || _core$ezauthJsonConf$3 === void 0 ? void 0 : _core$ezauthJsonConf$3.signTitle,
                        signContents: core.base64EncodeUTF8((_core$ezauthJsonConf$4 = core.ezauthJsonConf.signInfo) === null || _core$ezauthJsonConf$4 === void 0 ? void 0 : _core$ezauthJsonConf$4.signContents),
                        signDocEncType: (_core$ezauthJsonConf$5 = core.ezauthJsonConf.signInfo) === null || _core$ezauthJsonConf$5 === void 0 ? void 0 : _core$ezauthJsonConf$5.signDocEncType,
                        signDocHashType: (_core$ezauthJsonConf$6 = core.ezauthJsonConf.signInfo) === null || _core$ezauthJsonConf$6 === void 0 ? void 0 : _core$ezauthJsonConf$6.signDocHashType,
                        signContentInclude: (_core$ezauthJsonConf$7 = core.ezauthJsonConf.signInfo) !== null && _core$ezauthJsonConf$7 !== void 0 && _core$ezauthJsonConf$7.signContentInclude ? "Y" : "N"
                    },
                    extention: core.ezauthJsonConf.extention,
                    apiType: core.jsonSelectedProvider.apiType,
                    requestType,
                    businessInfoReq: core.ezauthJsonConf.businessInfoReq
                };
                if ([ "CLOUD", "QR", "A2A" ].includes(requestType)) {
                    var policysAllN = Object.fromEntries(Object.keys(reqJson.userInfo.policys).map(function(key) {
                        return [ key, "N" ];
                    }));
                    reqJson.userInfo = {
                        policys: policysAllN
                    };
                } else if (requestType === "PUSH" && core.basicInfo.deviceType && core.basicInfo.deviceType.indexOf("Mobile") > -1) {
                    var policysAllY = Object.fromEntries(Object.keys(reqJson.userInfo.policys).map(function(key) {
                        return [ key, "Y" ];
                    }));
                    reqJson.userInfo.policys = policysAllY;
                }
                var url = core.getApiUrl("request");
                core.sendRequest({
                    method: "POST",
                    url,
                    headers: {
                        "Content-Type": "application/json; charset=utf-8"
                    },
                    data: reqJson,
                    onSuccess: function onSuccess(data) {
                        core.sendAuthRequestPostAction(data, sendAuthRequestCallback);
                    },
                    onError: function onError() {
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_AUTH_REQUEST_FAIL.errstr);
                        core.getServiceErrorHandler(sendAuthRequestCallback);
                    },
                    onComplete: function onComplete() {
                        if (typeof _EzauthBlock__WEBPACK_IMPORTED_MODULE_5__["default"] !== "undefined") {
                            _EzauthBlock__WEBPACK_IMPORTED_MODULE_5__["default"].close();
                        }
                    }
                });
            };
            core.sendAuthRequestPostAction = function(data, sendAuthRequestCallback) {
                core.authReqResponse = data;
                var cliResponseData = {};
                if (data.resultCode == "2000") {
                    var apiOk = core.safeGetError("API_OK");
                    if (typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined") {
                        apiOk.result = data;
                    }
                    sendAuthRequestCallback(apiOk);
                } else if (data.resultCode == "1001") {
                    sendAuthRequestCallback(core.safeGetError("MLH2907"));
                } else {
                    cliResponseData.errstr = data.resultMsg;
                    sendAuthRequestCallback(cliResponseData);
                }
            };
            core.sendAuthResult = function(sendAuthResultCallback) {
                if (typeof _EzauthBlock__WEBPACK_IMPORTED_MODULE_5__["default"] !== "undefined") {} else {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].warn("EzauthBlock is not defined.");
                }
                var reqJson = {
                    siteInfo: core.ezauthJsonConf.siteInfo,
                    txId: core.authReqResponse.txId,
                    tokenId: core.authReqResponse.tokenId,
                    userToken: core.authReqResponse.userToken,
                    hubToken: core.authReqResponse.hubToken,
                    userReqYn: core.userReqYn
                };
                var url = core.getApiUrl("result");
                core.sendRequest({
                    method: "POST",
                    url,
                    headers: {
                        "Content-Type": "application/json; charset=utf-8"
                    },
                    data: reqJson,
                    onSuccess: function onSuccess(data) {
                        core.sendAuthResultPostAction(data, sendAuthResultCallback);
                    },
                    onError: function onError() {
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_AUTH_RESULT_FAIL.errstr);
                        core.getServiceErrorHandler(sendAuthResultCallback);
                    },
                    onComplete: function onComplete() {
                        if (typeof _EzauthBlock__WEBPACK_IMPORTED_MODULE_5__["default"] !== "undefined") {
                            _EzauthBlock__WEBPACK_IMPORTED_MODULE_5__["default"].close();
                        }
                    }
                });
            };
            core.sendAuthResultPostAction = function(data, sendAuthResultCallback) {
                core.authResultResponse = data;
                var cliResponseData = {};
                if (data.resultCode == "2000") {
                    sendAuthResultCallback(core.safeGetError("API_OK"));
                } else if (data.resultCode == "2906") {
                    sendAuthResultCallback(core.safeGetError("MLH2906"));
                } else if (data.resultCode == "2900") {
                    var errObj = _objectSpread({}, core.safeGetError("MLH2900"));
                    if (typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined") {
                        errObj.errstr = data.resultMsg ? data.resultMsg + "<br>" + _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2900.errstr : _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2900.errstr;
                    } else {
                        errObj.errstr = data.resultMsg || errObj.errstr;
                    }
                    sendAuthResultCallback(errObj);
                } else if (data.resultCode == "2990") {
                    sendAuthResultCallback(core.safeGetError("MLH2990"));
                } else if (data.resultCode == "1001") {
                    sendAuthResultCallback(core.safeGetError("MLH2907"));
                } else {
                    cliResponseData.errstr = data.resultMsg;
                    sendAuthResultCallback(cliResponseData);
                }
            };
            core.sendAuthCheck = function(sendAuthCheckCallback) {
                if (typeof _EzauthBlock__WEBPACK_IMPORTED_MODULE_5__["default"] !== "undefined") {} else {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].warn("EzauthBlock is not defined.");
                }
                var reqJson = {
                    siteInfo: core.ezauthJsonConf.siteInfo,
                    txId: core.authResultResponse.txId,
                    tokenId: core.authResultResponse.tokenId,
                    userToken: core.authResultResponse.userToken,
                    hubToken: core.authResultResponse.hubToken
                };
                var url = core.getApiUrl("authCheck");
                core.sendRequest({
                    method: "POST",
                    url,
                    headers: {
                        "Content-Type": "application/json; charset=utf-8"
                    },
                    data: reqJson,
                    onSuccess: function onSuccess(data) {
                        core.sendAuthCheckPostAction(data, sendAuthCheckCallback);
                    },
                    onError: function onError() {
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_AUTH_CHECK_FAIL.errstr);
                        core.getServiceErrorHandler(sendAuthCheckCallback);
                    }
                });
            };
            core.endecryptReq = function(data, endecryptReqCallback) {
                var decUrl = core.getApiUrl("endecrypt");
                core.sendRequest({
                    method: "POST",
                    url: decUrl,
                    headers: {
                        "Content-Type": "application/json; charset=utf-8"
                    },
                    data,
                    onSuccess: function onSuccess(res) {
                        endecryptReqCallback && endecryptReqCallback(res);
                    },
                    onError: function onError(error) {
                        endecryptReqCallback && endecryptReqCallback(error);
                    }
                });
            };
            core.sendAuthCheckPostAction = function(data, sendAuthCheckCallback) {
                core.authResultResponse = data;
                var cliResponseData = {};
                if (data.resultCode == "2000") {
                    cliResponseData = core.safeGetError("API_OK");
                    cliResponseData.resultCode = data.resultCode;
                    cliResponseData.resultMsg = data.resultMsg;
                    cliResponseData.result = data.resultData;
                    cliResponseData.txId = data.txId;
                    cliResponseData.tokenId = data.tokenId;
                    sendAuthCheckCallback(cliResponseData);
                } else {
                    cliResponseData = core.safeGetError("MLH9990");
                    cliResponseData.resultCode = data.resultCode;
                    sendAuthCheckCallback(cliResponseData);
                }
            };
            core.isValidUserInfo = function() {
                if (typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"] === "undefined" || typeof window.parent.EzauthConfig === "undefined") {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].error("EzauthAlert or window.parent.EzauthConfig is not defined.");
                    return false;
                }
                if (Object.keys(EzauthCore.jsonSelectedProvider).length === 0) {
                    _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.certNotSelected);
                    return false;
                }
                var isAuthTypeSelected = document.querySelectorAll(".cloud-button.on, .app-push-button.on, .app2app-button.on").length > 0;
                if (!isAuthTypeSelected) {
                    _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.authTypeNotSelected);
                    return false;
                }
                var cloudBtn = document.getElementById("cloud-btn");
                var isCloudActive = cloudBtn && cloudBtn.classList.contains("on");
                var lis = document.querySelectorAll("#EzauthContainer .body .step1 .user-info .body li");
                var _iterator = _createForOfIteratorHelper(lis), _step;
                try {
                    var _loop = function _loop() {
                        var li = _step.value;
                        if (li.style.display === "none") return 0;
                        if (li.classList.contains("name")) {
                            var input = li.querySelector("input");
                            var value = (input === null || input === void 0 ? void 0 : input.value.replace(/ /g, "")) || "";
                            if (value.length === 0) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.nameNotInput, null, null, null, null, function() {
                                    return input.focus();
                                });
                                return {
                                    v: false
                                };
                            }
                            if (value.length > 50) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.nameExceededCharacterCount, null, null, null, null, function() {
                                    return input.focus();
                                });
                                return {
                                    v: false
                                };
                            }
                        }
                        if (li.classList.contains("birth")) {
                            var _input = li.querySelector("input");
                            var _value = (_input === null || _input === void 0 ? void 0 : _input.value.replace(/ /g, "")) || "";
                            if (_value.length !== 8) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.birthError, null, null, null, null, function() {
                                    return _input.focus();
                                });
                                return {
                                    v: false
                                };
                            }
                        }
                        if (li.classList.contains("hp") && !isCloudActive) {
                            var _li$querySelector, _li$querySelector2;
                            var telPrefix = ((_li$querySelector = li.querySelector("input.sel_telnum")) === null || _li$querySelector === void 0 ? void 0 : _li$querySelector.value) || "010";
                            var telRest = ((_li$querySelector2 = li.querySelector("input.telnum_end")) === null || _li$querySelector2 === void 0 ? void 0 : _li$querySelector2.value.replace(/ /g, "")) || "";
                            var fullPhone = telPrefix + telRest;
                            if (telRest.length === 0 || fullPhone.length < 10 || fullPhone.length > 11) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.hpNumError, null, null, null, null, function() {
                                    var _li$querySelector3;
                                    return (_li$querySelector3 = li.querySelector("input")) === null || _li$querySelector3 === void 0 ? void 0 : _li$querySelector3.focus();
                                });
                                return {
                                    v: false
                                };
                            }
                            var telco = li.querySelector(".telco");
                            if (telco && telco.style.display !== "none" && telco.selectedIndex === 0) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.mobileCompanySelect, null, null, null, null, function() {
                                    return telco.focus();
                                });
                                return {
                                    v: false
                                };
                            }
                        }
                        if (li.classList.contains("biz-registration-number") && !isCloudActive) {
                            var _input2 = li.querySelector("input");
                            var _value2 = (_input2 === null || _input2 === void 0 ? void 0 : _input2.value.replace(/ /g, "")) || "";
                            if (_value2.length === 0) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.bizNumEmpty, null, null, null, null, function() {
                                    return _input2.focus();
                                });
                                return {
                                    v: false
                                };
                            }
                            if (_value2.length !== 10) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.bizNumInvalid, null, null, null, null, function() {
                                    return _input2.focus();
                                });
                                return {
                                    v: false
                                };
                            }
                        }
                        if (li.classList.contains("ssn")) {
                            var ssn1 = li.querySelector("input.ssn1");
                            var ssn2 = li.querySelector("input.ssn2");
                            if (ssn1 && ssn1.value.replace(/ /g, "").length !== 6) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.ssn1Error, null, null, null, null, function() {
                                    return ssn1.focus();
                                });
                                return {
                                    v: false
                                };
                            }
                            if (ssn2 && ssn2.value.replace(/ /g, "").length !== 7) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(window.parent.EzauthConfig.ui.errorText.ssn2Error, null, null, null, null, function() {
                                    return ssn2.focus();
                                });
                                return {
                                    v: false
                                };
                            }
                        }
                    }, _ret;
                    for (_iterator.s(); !(_step = _iterator.n()).done; ) {
                        _ret = _loop();
                        if (_ret === 0) continue;
                        if (_ret) return _ret.v;
                    }
                } catch (err) {
                    _iterator.e(err);
                } finally {
                    _iterator.f();
                }
                return true;
            };
            core.isValidPolicys = function() {
                if (typeof window.parent.EzauthConfig === "undefined") {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].error("window.parent.EzauthConfig is not defined.");
                    return false;
                }
                var policysObj = document.querySelectorAll("#EzauthContainer .body .step1 .policys li.policy");
                for (var i = 0; i < policysObj.length; i++) {
                    var policyObj = policysObj[i];
                    if (policyObj.style.display === "none") continue;
                    if (policyObj.getAttribute("data-required") === "Y") {
                        var checkboxImg = policyObj.querySelector("img.checkbox");
                        if (checkboxImg && !checkboxImg.classList.contains("on")) {
                            return false;
                        }
                    }
                }
                return true;
            };
            core.cleanUserInfo = function() {
                var classNameList = [ {
                    li: "biz-registration-number"
                }, {
                    li: "name"
                }, {
                    li: "birth"
                }, {
                    li: "hp",
                    input: "telnum_end"
                }, {
                    li: "ssn",
                    input: "ssn1"
                }, {
                    li: "ssn",
                    input: "ssn2"
                } ];
                for (var i = 0; i < classNameList.length; i++) {
                    if (typeof classNameList[i].input === "undefined") {
                        var inputElement = document.querySelector("#EzauthContainer .body .step1 .user-info li." + classNameList[i].li + " input");
                        if (inputElement) inputElement.value = "";
                    } else {
                        var _inputElement = document.querySelector("#EzauthContainer .body .step1 .user-info li." + classNameList[i].li + " input." + classNameList[i].input);
                        if (_inputElement) _inputElement.value = "";
                    }
                }
            };
            core.cleanPolicys = function() {
                var policysImgs = document.querySelectorAll("#EzauthContainer .body .step1 .policys [class*='policy'] img.checkbox");
                for (var i = 0; i < policysImgs.length; i++) {
                    var policysImg = policysImgs[i];
                    if (policysImg.classList.contains("on")) {
                        policysImg.classList.remove("on");
                        if (core.basicInfo) {
                            policysImg.setAttribute("src", core.basicInfo.ezauthRootPath + "assets/img/form_checkbox_nor.png");
                        }
                    }
                }
            };
            core.getJsonConf = function() {
                return core.ezauthJsonConf;
            };
            core.startPollingAuthStatus = function(txid, pollingInterval, pollingCnt, signal) {
                if (!core.ezauthJsonConf.usePolling) return;
                if (!signal) return;
                var showLoadingIcon = function showLoadingIcon(selector) {
                    var icon = document.querySelector("".concat(selector, " .icon_auth_loading"));
                    if (icon) icon.style.setProperty("display", "flex", "important");
                };
                var hideLoadingIcon = function hideLoadingIcon(selector) {
                    var icon = document.querySelector("".concat(selector, " .icon_auth_loading"));
                    if (icon) icon.style.setProperty("display", "none", "important");
                };
                var selector = null;
                if (core.basicInfo.deviceType === "PC") {
                    if (core.basicInfo.requestType === "QR") {
                        selector = "#EzauthContainer .body .step1 .buttons .complete-auth";
                    } else if (core.basicInfo.requestType === "PUSH") {
                        selector = "#EzauthContainer .body .step2 .buttons .complete-auth";
                    }
                } else {
                    if (core.basicInfo.requestType !== "CLOUD") {
                        selector = "#EzauthContainer .body .step2 .buttons .complete-auth";
                    }
                }
                if (selector) showLoadingIcon(selector);
                var isFetching = false;
                var intervalMs = pollingInterval * 1e3;
                var timeoutMs = pollingInterval * pollingCnt * 1e3;
                var intervalId = setInterval(function() {
                    if (isFetching || signal.aborted) return;
                    isFetching = true;
                    core.userReqYn = "N";
                    core.sendAuthResult(function(data) {
                        if (signal.aborted) return;
                        if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                            cleanup();
                            core.sendAuthCheck(function(data) {
                                if (signal.aborted) return;
                                if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                                    if (EzauthCore.ezauthJsonConf.service === "authBiz") {
                                        var nameEl = core.getUserInfoElement("name");
                                        var phonePrefixEl = core.getUserInfoElement("phonePrefix");
                                        var phoneSuffixEl = core.getUserInfoElement("phone");
                                        var birthEl = core.getUserInfoElement("birth");
                                        var bizEl = core.getUserInfoElement("biz");
                                        var ssn1El = core.getUserInfoElement("ssn1");
                                        var ssn2El = core.getUserInfoElement("ssn2");
                                        var telcoEl = core.getUserInfoElement("telco");
                                        var entries = [ [ "name", nameEl ], [ "phone", {
                                            prefixEl: phonePrefixEl,
                                            suffixEl: phoneSuffixEl
                                        } ], [ "birthday", birthEl ], [ "businessNumber", bizEl ], [ "ssn1", ssn1El ], [ "ssn2", ssn2El ], [ "telcoTycd", telcoEl ] ];
                                        var reqBody = core.makeEncBody(entries);
                                        var body = {
                                            data: reqBody,
                                            code: "encrypt"
                                        };
                                        core.endecryptReq(body, function(decryptedObj) {
                                            if (!decryptedObj || (0, _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_2__["default"])(decryptedObj) !== "object" || decryptedObj.resultCode !== "2000") return;
                                            var encData = decryptedObj.data;
                                            sessionStorage.setItem("EZAuth", encData);
                                            core.cleanUserInfo();
                                            core.cleanPolicys();
                                            core.sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK, data);
                                        });
                                    } else {
                                        core.sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK, data);
                                    }
                                } else {
                                    _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(data.errstr);
                                }
                            });
                        } else if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2990.errno) {
                            isFetching = false;
                        } else {
                            cleanup();
                            _EzauthAlert__WEBPACK_IMPORTED_MODULE_3__["default"].show(data.errstr);
                        }
                    });
                }, intervalMs);
                var timeoutId = setTimeout(function() {
                    cleanup();
                }, timeoutMs);
                var cleanup = function cleanup() {
                    clearInterval(intervalId);
                    clearTimeout(timeoutId);
                    if (selector) hideLoadingIcon(selector);
                };
                signal.addEventListener("abort", function() {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_6__["default"].info("[Polling abort] txid=".concat(txid));
                    cleanup();
                });
            };
            core.makeEncBody = function(elementArray) {
                var data = {};
                var _iterator2 = _createForOfIteratorHelper(elementArray), _step2;
                try {
                    for (_iterator2.s(); !(_step2 = _iterator2.n()).done; ) {
                        var _step2$value = (0, _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__["default"])(_step2.value, 2), key = _step2$value[0], el = _step2$value[1];
                        if (!el) continue;
                        var raw = "";
                        if (key === "phone" && (0, _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_2__["default"])(el) === "object" && el.prefixEl && el.suffixEl) {
                            var _el$prefixEl$value, _el$suffixEl$value;
                            var prefix = ((_el$prefixEl$value = el.prefixEl.value) !== null && _el$prefixEl$value !== void 0 ? _el$prefixEl$value : "").trim();
                            var suffix = ((_el$suffixEl$value = el.suffixEl.value) !== null && _el$suffixEl$value !== void 0 ? _el$suffixEl$value : "").trim();
                            raw = prefix + suffix;
                        } else {
                            var _el$value;
                            raw = ((_el$value = el.value) !== null && _el$value !== void 0 ? _el$value : "").toString().trim();
                        }
                        if (raw === "") continue;
                        var needStrip = [ "phone", "birthday", "businessNumber", "ssn1", "ssn2" ].includes(key);
                        if (needStrip) raw = raw.replace(/\s/g, "");
                        if (key === "telcoTycd" && typeof el.selectedIndex === "number" && el.selectedIndex === 0) {
                            continue;
                        }
                        data[key] = raw;
                    }
                } catch (err) {
                    _iterator2.e(err);
                } finally {
                    _iterator2.f();
                }
                return JSON.stringify(data);
            };
            core.base64EncodeUTF8 = function(input) {
                var encodeStr = function encodeStr(str) {
                    var bytes = (new TextEncoder).encode(str);
                    var binary = "";
                    var _iterator3 = _createForOfIteratorHelper(bytes), _step3;
                    try {
                        for (_iterator3.s(); !(_step3 = _iterator3.n()).done; ) {
                            var b = _step3.value;
                            binary += String.fromCharCode(b);
                        }
                    } catch (err) {
                        _iterator3.e(err);
                    } finally {
                        _iterator3.f();
                    }
                    return btoa(binary);
                };
                if (Array.isArray(input)) {
                    return input.map(function(item) {
                        return encodeStr(item);
                    });
                } else {
                    return encodeStr(input);
                }
            };
            core.base64DecodeUTF8 = function(input) {
                var decodeStr = function decodeStr(base64) {
                    var binary = atob(base64);
                    var bytes = new Uint8Array(binary.length);
                    for (var i = 0; i < binary.length; i++) {
                        bytes[i] = binary.charCodeAt(i);
                    }
                    return (new TextDecoder).decode(bytes);
                };
                if (Array.isArray(input)) {
                    return input.map(function(item) {
                        return decodeStr(item);
                    });
                } else {
                    return decodeStr(input);
                }
            };
        })(EzauthCore);
        const __WEBPACK_DEFAULT_EXPORT__ = EzauthCore;
    },
    "./src/ezauth/js/EzauthErrorHandler.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => __WEBPACK_DEFAULT_EXPORT__
        });
        var EzauthErrorHandler = {};
        (function(errorHandler) {
            var m_lastErrorNo = 0;
            var m_lastErrorMsg = "";
            errorHandler.error = {
                API_OK: {
                    errno: 0,
                    errstr: ""
                },
                API_INITIALIZED: {
                    errno: 10,
                    errstr: "모듈이 초기화 되었습니다"
                },
                API_AUTH_REQUEST_FAIL: {
                    errno: 111,
                    errstr: "간편인증 요청을 실패 하였습니다."
                },
                API_AUTH_RESULT_FAIL: {
                    errno: 121,
                    errstr: "간편인증 결과 요청을 실패 하였습니다."
                },
                API_AUTH_CHECK_FAIL: {
                    errno: 131,
                    errstr: "간편인증 확인 요청을 실패 하였습니다."
                },
                API_SIMPLEAUTH_CANCEL: {
                    errno: 302,
                    errstr: "간편인증창 닫기 버튼을 클릭 하였습니다."
                },
                API_SIMPLESIGN_CANCEL: {
                    errno: 303,
                    errstr: "간편서명(개인)창을 취소 하였습니다"
                },
                API_SIMPLESIGNBIZ_CANCEL: {
                    errno: 307,
                    errstr: "간편서명(개인사업자)창을 취소 하였습니다"
                },
                API_CLOUD_FAIL: {
                    errno: 308,
                    errstr: "클라우드 간편인증을 실패하였습니다."
                },
                API_CLOUD_CANCEL: {
                    errno: 309,
                    errstr: "클라우드 인증창을 취소하였습니다."
                },
                API_CLOUD_INVALID_URL: {
                    errno: 310,
                    errstr: "잘못된 클라우드 URL 입니다"
                },
                API_USER_SIGN_PROCESSING: {
                    errno: 1001,
                    errstr: "현재 사용자의 서명 프로세스가 진행중입니다."
                },
                API_INVALID_SITE_ID: {
                    errno: 1002,
                    errstr: "siteId가 유효하지 않습니다."
                },
                API_FAIL_TO_GET_UI_CONF: {
                    errno: 1003,
                    errstr: "서버에서 화면설정을 가져오는데 실패하였습니다."
                },
                API_NOT_INITIALIZED: {
                    errno: 1004,
                    errstr: "모듈 초기화에 실패하였습니다."
                },
                API_FILE_NOT_FOUND_ERROR: {
                    errno: 1005,
                    errstr: "간편인증 설정 JSON 파일을 찾을 수 없습니다."
                },
                API_PKI_PROCESS_ERR: {
                    errno: 1006,
                    errstr: "PKI 에러가 발생했습니다."
                },
                API_SIMPLEAUTH_ERROR: {
                    errno: 1008,
                    errstr: "간편인증(개인) 처리중 오류가 발생하였습니다"
                },
                API_SIMPLESIGN_ERROR: {
                    errno: 1009,
                    errstr: "간편서명(개인) 처리중 오류가 발생하였습니다"
                },
                API_SIMPLEAUTHBIZ_ERROR: {
                    errno: 1010,
                    errstr: "간편인증(개인사업자) 처리중 오류가 발생하였습니다"
                },
                API_SIMPLESIGNBIZ_ERROR: {
                    errno: 1011,
                    errstr: "간편서명(개인사업자) 처리중 오류가 발생하였습니다"
                },
                API_FAIL_TO_GET_TOKEN: {
                    errno: 1012,
                    errstr: "토큰 재발급 중 오류가 발생하였습니다"
                },
                MLH2001: {
                    errno: 2001,
                    errstr: "환경 설정 값이 없습니다. 관리자에게 문의하세요."
                },
                MLH2002: {
                    errno: 2002,
                    errstr: "허용되지 않은 이용기관(or 사이트) 입니다. 관리자에게 문의하세요."
                },
                MLH2003: {
                    errno: 2003,
                    errstr: "사용자 토큰 생성 에러입니다. 관리자에게 문의하세요."
                },
                MLH2004: {
                    errno: 2004,
                    errstr: "HUB 토큰 생성 에러입니다. 관리자에게 문의하세요."
                },
                MLH2005: {
                    errno: 2005,
                    errstr: "현재 서비스하지 않는 인증 사업자입니다."
                },
                MLH2901: {
                    errno: 2901,
                    errstr: "인증사업자 요청메시지 에러입니다. 관리자에게 문의하세요."
                },
                MLH2902: {
                    errno: 2902,
                    errstr: "인증사업자 응답메시지 에러입니다. 관리자에게 문의하세요."
                },
                MLH2903: {
                    errno: 2903,
                    errstr: "인증사업자 HTTP STATUS CODE 에러입니다. 관리자에게 문의하세요."
                },
                MLH2904: {
                    errno: 2904,
                    errstr: "인증사업자 통신 에러입니다. 관리자에게 문의하세요."
                },
                MLH2905: {
                    errno: 2905,
                    errstr: "인증사업자 응답이 없습니다. 관리자에게 문의하세요."
                },
                MLH2906: {
                    errno: 2906,
                    errstr: "사용자가 취소한 요청입니다."
                },
                MLH2907: {
                    errno: 2907,
                    errstr: "간편인증 화면을 닫고 다시 실행해주세요.<br>사유 : 토큰 유효 시간 만료"
                },
                MLH2900: {
                    errno: 2900,
                    errstr: "간편인증 화면을 닫고 다시 실행해주세요."
                },
                MLH2990: {
                    errno: 2990,
                    errstr: "사용자 인증이 완료되지 않았습니다."
                },
                MLH9000: {
                    errno: 9e3,
                    errstr: "이름 및 생년월일을 입력해주십시오."
                },
                MLH9001: {
                    errno: 9001,
                    errstr: "필수항목 동의를 해주십시오."
                },
                MLH9002: {
                    errno: 9002,
                    errstr: "인증서비스를 선택해 주십시오."
                },
                MLH9003: {
                    errno: 9003,
                    errstr: "이름을 다시 입력해 주십시오."
                },
                MLH9004: {
                    errno: 9004,
                    errstr: "주민번호를 다시 입력해 주십시오."
                },
                MLH9005: {
                    errno: 9005,
                    errstr: "유효하지 않은 주민등록번호 입니다."
                },
                MLH9006: {
                    errno: 9006,
                    errstr: "생년월일을 다시 입력해주십시오."
                },
                MLH9007: {
                    errno: 9007,
                    errstr: "핸드폰번호를 다시 입력해 주십시오."
                },
                MLH9008: {
                    errno: 9008,
                    errstr: "통신사를 선택해 주십시오."
                },
                MLH9009: {
                    errno: 9009,
                    errstr: "이름 및 사업자등록번호를 입력해주십시오."
                },
                MLH9010: {
                    errno: 9010,
                    errstr: "사업자등록번호를 다시 입력해주십시오."
                },
                MLH9990: {
                    errno: 9990,
                    errstr: "검증에 실패했습니다."
                },
                MLH9991: {
                    errno: 9991,
                    errstr: "인증에 실패했습니다."
                }
            };
            errorHandler.clearError = function() {
                m_lastErrorNo = 0;
                m_lastErrorMsg = "";
            };
            errorHandler.getLastError = function() {
                return m_lastErrorNo;
            };
            errorHandler.getLastErrorMessage = function() {
                return m_lastErrorMsg;
            };
            errorHandler.setLastError = function(errno, errmsg) {
                if (errno === null || errmsg === null || errno === undefined || errmsg === undefined) {
                    return;
                }
                m_lastErrorNo = errno;
                m_lastErrorMsg = errmsg;
                if (arguments.length === 3) {
                    var message = arguments[2];
                    if (message === undefined || message === null) {
                        message = (new Error).stack.toString();
                    }
                    m_lastErrorMsg = m_lastErrorMsg + " (" + message + ")";
                }
            };
        })(EzauthErrorHandler);
        const __WEBPACK_DEFAULT_EXPORT__ = EzauthErrorHandler;
    },
    "./src/ezauth/js/EzauthModal.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => __WEBPACK_DEFAULT_EXPORT__
        });
        var _utils_Logger_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./src/ezauth/utils/Logger.js");
        var EzauthModal = {};
        (function(modal) {
            modal.isInitialized = false;
            modal.path = "";
            modal.policyId = "";
            modal.titleOption = {
                use: false,
                title: ""
            };
            modal.closeButtonOption = {
                use: true,
                title: ""
            };
            modal.option = {};
            var confirmButtonDelegateHandler = null;
            modal.init = function() {
                if (modal.isInitialized === true) {
                    return;
                }
                modal.isInitialized = true;
            };
            modal.close = function() {
                var modalElement = document.getElementById("EzauthModal");
                if (modalElement) {
                    modalElement.remove();
                }
                modal.isInitialized = false;
                var originalTrigger = document.querySelector("#".concat(modal.policyId, " .button-show"));
                if (originalTrigger) {
                    originalTrigger.focus();
                }
            };
            modal.setFocusTrap = function() {
                var modalElem = document.getElementById("EzauthModal");
                if (!modalElem) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_0__["default"].error("ID 'EzauthModal'를 가진 요소를 찾을 수 없습니다.");
                    return;
                }
                var FOCUSABLE_SELECTOR = '\n            a[href],\n            button:not([disabled]),\n            input:not([disabled]),\n            select:not([disabled]),\n            textarea:not([disabled]),\n            [tabindex]:not([tabindex="-1"])\n        ';
                var focusableNodes = modalElem.querySelectorAll(FOCUSABLE_SELECTOR);
                var focusableElements = [];
                for (var i = 0; i < focusableNodes.length; i++) {
                    focusableElements.push(focusableNodes[i]);
                }
                if (focusableElements.length === 0) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_0__["default"].warn("모달 영역 내에 포커스 가능한 요소가 없습니다.");
                    return;
                }
                var firstFocusableElement = focusableElements[0];
                var lastFocusableElement = focusableElements[focusableElements.length - 1];
                modalElem.addEventListener("keydown", function(e) {
                    var isTabPressed = e.key === "Tab" || e.keyCode === 9;
                    if (!isTabPressed) {
                        return;
                    }
                    if (e.shiftKey) {
                        if (document.activeElement === firstFocusableElement) {
                            lastFocusableElement.focus();
                            e.preventDefault();
                        }
                    } else {
                        if (document.activeElement === lastFocusableElement) {
                            firstFocusableElement.focus();
                            e.preventDefault();
                        }
                    }
                });
            };
            modal.show = function(policyId, path, titleOption, closeButtonOption, option) {
                modal.init();
                modal.path = path;
                modal.policyId = policyId;
                modal.titleOption = titleOption || {
                    use: false,
                    title: ""
                };
                modal.closeButtonOption = closeButtonOption || {
                    use: true,
                    title: ""
                };
                modal.option = option || {};
                var existingModal = document.getElementById("EzauthModal");
                if (existingModal) {
                    existingModal.remove();
                }
                var modalDomStr = '\n            <div id="EzauthModal">\n                <div class="encase">\n                    <div class="Modal-Wrap">\n                        <section class="content"></section>\n                    </div>\n                </div>\n            </div>\n        ';
                var tempDiv = document.createElement("div");
                tempDiv.innerHTML = modalDomStr.trim();
                var modalDomObj = tempDiv.firstChild;
                var modalWrap = modalDomObj.querySelector(".Modal-Wrap");
                if (modal.titleOption.use && modalWrap) {
                    var titleSection = document.createElement("section");
                    titleSection.classList.add("title");
                    var titleDiv = document.createElement("div");
                    titleDiv.setAttribute("tabindex", "-1");
                    titleDiv.textContent = modal.titleOption.title;
                    titleSection.appendChild(titleDiv);
                    modalWrap.prepend(titleSection);
                }
                if (modal.closeButtonOption.use && modalWrap) {
                    var buttonsSection = document.createElement("section");
                    buttonsSection.classList.add("buttons");
                    var confirmButton = document.createElement("div");
                    confirmButton.classList.add("button", "confirm");
                    confirmButton.setAttribute("role", "button");
                    confirmButton.setAttribute("tabindex", "0");
                    confirmButton.textContent = modal.closeButtonOption.title;
                    confirmButton.addEventListener("click", function() {
                        modal.close();
                    });
                    confirmButton.addEventListener("keyup", function(event) {
                        if (event.which === 13 || event.which === 32) {
                            modal.close();
                        }
                    });
                    buttonsSection.appendChild(confirmButton);
                    modalWrap.appendChild(buttonsSection);
                }
                var contentSection = modalDomObj.querySelector(".content");
                var xhr = new XMLHttpRequest;
                xhr.open("GET", modal.path, true);
                xhr.onload = function() {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        if (contentSection) {
                            contentSection.innerHTML = xhr.responseText;
                        }
                        modal.setFocusTrap();
                        var _confirmButton = modalDomObj.querySelector(".button.confirm");
                        if (_confirmButton) {
                            _confirmButton.focus();
                        }
                        var modalCloseBtn = modalDomObj.querySelector("#EzauthModal .btn_modal_close");
                        if (modalCloseBtn) {
                            modalCloseBtn.addEventListener("click", function(event) {
                                event.preventDefault();
                                modal.close();
                            });
                            modalCloseBtn.addEventListener("keydown", function(event) {
                                if (event.which === 13) {
                                    event.preventDefault();
                                }
                            });
                            modalCloseBtn.addEventListener("keyup", function(event) {
                                event.preventDefault();
                                if (event.which === 13 || event.which === 32) {
                                    modal.close();
                                }
                            });
                        }
                    } else {
                        _utils_Logger_js__WEBPACK_IMPORTED_MODULE_0__["default"].error("Failed to load modal content:", xhr.statusText);
                        if (contentSection) {
                            contentSection.innerHTML = "<p>콘텐츠 로드 실패: ".concat(xhr.statusText, "</p>");
                        }
                    }
                };
                xhr.onerror = function() {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_0__["default"].error("Network error while loading modal content.");
                    if (contentSection) {
                        contentSection.innerHTML = "<p>네트워크 오류로 콘텐츠를 로드할 수 없습니다.</p>";
                    }
                };
                xhr.send();
                document.body.appendChild(modalDomObj);
            };
            modal.init();
        })(EzauthModal);
        const __WEBPACK_DEFAULT_EXPORT__ = EzauthModal;
    },
    "./src/ezauth/js/EzauthUtils.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => __WEBPACK_DEFAULT_EXPORT__
        });
        var EzauthUtils = {};
        (function(utils) {
            utils.setLocalStorageDataAsJSON = function(storageName, key, value) {
                var storageObject = JSON.parse(localStorage.getItem(storageName)) || {};
                storageObject[key] = value;
                localStorage.setItem(storageName, JSON.stringify(storageObject));
            };
            utils.getLocalStorageDataAsJSON = function(storageName) {
                return JSON.parse(localStorage.getItem(storageName)) || {};
            };
            utils.removeLocalStorage = function(storageName) {
                localStorage.removeItem(storageName);
            };
            utils.getOrigin = function(url) {
                try {
                    return new URL(url).origin;
                } catch (error) {
                    console.error("Invalid URL:", error);
                    return null;
                }
            };
        })(EzauthUtils);
        const __WEBPACK_DEFAULT_EXPORT__ = EzauthUtils;
    },
    "./src/ezauth/utils/Logger.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => __WEBPACK_DEFAULT_EXPORT__
        });
        var LOGGER_ENABLED = "true" === "true";
        var LOG_LEVEL = "info" || 0;
        var levels = {
            error: 0,
            warn: 1,
            info: 2
        };
        var currentLevelName = LOG_LEVEL;
        var currentLevel = levels[currentLevelName];
        var getTimestamp = function getTimestamp() {
            var now = new Date;
            var year = now.getFullYear();
            var month = String(now.getMonth() + 1).padStart(2, "0");
            var day = String(now.getDate()).padStart(2, "0");
            var hours = String(now.getHours()).padStart(2, "0");
            var minutes = String(now.getMinutes()).padStart(2, "0");
            var seconds = String(now.getSeconds()).padStart(2, "0");
            var milliseconds = String(now.getMilliseconds()).padStart(3, "0");
            return "".concat(year, "-").concat(month, "-").concat(day, ",").concat(hours, ":").concat(minutes, ":").concat(seconds, ".").concat(milliseconds);
        };
        var logMessage = function logMessage(level, consoleFn) {
            if (!LOGGER_ENABLED) return;
            if (levels[level] > currentLevel) return;
            for (var _len = arguments.length, args = new Array(_len > 2 ? _len - 2 : 0), _key = 2; _key < _len; _key++) {
                args[_key - 2] = arguments[_key];
            }
            consoleFn.apply(void 0, [ "[BEA][".concat(getTimestamp(), "] [").concat(level.toUpperCase(), "]") ].concat(args));
        };
        var logger = {
            error: function error() {
                for (var _len2 = arguments.length, args = new Array(_len2), _key2 = 0; _key2 < _len2; _key2++) {
                    args[_key2] = arguments[_key2];
                }
                logMessage.apply(void 0, [ "error", console.error ].concat(args));
            },
            warn: function warn() {
                for (var _len3 = arguments.length, args = new Array(_len3), _key3 = 0; _key3 < _len3; _key3++) {
                    args[_key3] = arguments[_key3];
                }
                logMessage.apply(void 0, [ "warn", console.warn ].concat(args));
            },
            info: function info() {
                for (var _len4 = arguments.length, args = new Array(_len4), _key4 = 0; _key4 < _len4; _key4++) {
                    args[_key4] = arguments[_key4];
                }
                logMessage.apply(void 0, [ "info", console.log ].concat(args));
            }
        };
        const __WEBPACK_DEFAULT_EXPORT__ = logger;
    },
    "./node_modules/dijkstrajs/dijkstra.js"(module) {
        "use strict";
        var dijkstra = {
            single_source_shortest_paths: function(graph, s, d) {
                var predecessors = {};
                var costs = {};
                costs[s] = 0;
                var open = dijkstra.PriorityQueue.make();
                open.push(s, 0);
                var closest, u, v, cost_of_s_to_u, adjacent_nodes, cost_of_e, cost_of_s_to_u_plus_cost_of_e, cost_of_s_to_v, first_visit;
                while (!open.empty()) {
                    closest = open.pop();
                    u = closest.value;
                    cost_of_s_to_u = closest.cost;
                    adjacent_nodes = graph[u] || {};
                    for (v in adjacent_nodes) {
                        if (adjacent_nodes.hasOwnProperty(v)) {
                            cost_of_e = adjacent_nodes[v];
                            cost_of_s_to_u_plus_cost_of_e = cost_of_s_to_u + cost_of_e;
                            cost_of_s_to_v = costs[v];
                            first_visit = typeof costs[v] === "undefined";
                            if (first_visit || cost_of_s_to_v > cost_of_s_to_u_plus_cost_of_e) {
                                costs[v] = cost_of_s_to_u_plus_cost_of_e;
                                open.push(v, cost_of_s_to_u_plus_cost_of_e);
                                predecessors[v] = u;
                            }
                        }
                    }
                }
                if (typeof d !== "undefined" && typeof costs[d] === "undefined") {
                    var msg = [ "Could not find a path from ", s, " to ", d, "." ].join("");
                    throw new Error(msg);
                }
                return predecessors;
            },
            extract_shortest_path_from_predecessor_list: function(predecessors, d) {
                var nodes = [];
                var u = d;
                var predecessor;
                while (u) {
                    nodes.push(u);
                    predecessor = predecessors[u];
                    u = predecessors[u];
                }
                nodes.reverse();
                return nodes;
            },
            find_path: function(graph, s, d) {
                var predecessors = dijkstra.single_source_shortest_paths(graph, s, d);
                return dijkstra.extract_shortest_path_from_predecessor_list(predecessors, d);
            },
            PriorityQueue: {
                make: function(opts) {
                    var T = dijkstra.PriorityQueue, t = {}, key;
                    opts = opts || {};
                    for (key in T) {
                        if (T.hasOwnProperty(key)) {
                            t[key] = T[key];
                        }
                    }
                    t.queue = [];
                    t.sorter = opts.sorter || T.default_sorter;
                    return t;
                },
                default_sorter: function(a, b) {
                    return a.cost - b.cost;
                },
                push: function(value, cost) {
                    var item = {
                        value,
                        cost
                    };
                    this.queue.push(item);
                    this.queue.sort(this.sorter);
                },
                pop: function() {
                    return this.queue.shift();
                },
                empty: function() {
                    return this.queue.length === 0;
                }
            }
        };
        if (true) {
            module.exports = dijkstra;
        }
    },
    "./node_modules/qrcode/lib/browser.js"(__unused_webpack_module, exports, __webpack_require__) {
        const canPromise = __webpack_require__("./node_modules/qrcode/lib/can-promise.js");
        const QRCode = __webpack_require__("./node_modules/qrcode/lib/core/qrcode.js");
        const CanvasRenderer = __webpack_require__("./node_modules/qrcode/lib/renderer/canvas.js");
        const SvgRenderer = __webpack_require__("./node_modules/qrcode/lib/renderer/svg-tag.js");
        function renderCanvas(renderFunc, canvas, text, opts, cb) {
            const args = [].slice.call(arguments, 1);
            const argsNum = args.length;
            const isLastArgCb = typeof args[argsNum - 1] === "function";
            if (!isLastArgCb && !canPromise()) {
                throw new Error("Callback required as last argument");
            }
            if (isLastArgCb) {
                if (argsNum < 2) {
                    throw new Error("Too few arguments provided");
                }
                if (argsNum === 2) {
                    cb = text;
                    text = canvas;
                    canvas = opts = undefined;
                } else if (argsNum === 3) {
                    if (canvas.getContext && typeof cb === "undefined") {
                        cb = opts;
                        opts = undefined;
                    } else {
                        cb = opts;
                        opts = text;
                        text = canvas;
                        canvas = undefined;
                    }
                }
            } else {
                if (argsNum < 1) {
                    throw new Error("Too few arguments provided");
                }
                if (argsNum === 1) {
                    text = canvas;
                    canvas = opts = undefined;
                } else if (argsNum === 2 && !canvas.getContext) {
                    opts = text;
                    text = canvas;
                    canvas = undefined;
                }
                return new Promise(function(resolve, reject) {
                    try {
                        const data = QRCode.create(text, opts);
                        resolve(renderFunc(data, canvas, opts));
                    } catch (e) {
                        reject(e);
                    }
                });
            }
            try {
                const data = QRCode.create(text, opts);
                cb(null, renderFunc(data, canvas, opts));
            } catch (e) {
                cb(e);
            }
        }
        exports.create = QRCode.create;
        exports.toCanvas = renderCanvas.bind(null, CanvasRenderer.render);
        exports.toDataURL = renderCanvas.bind(null, CanvasRenderer.renderToDataURL);
        exports.toString = renderCanvas.bind(null, function(data, _, opts) {
            return SvgRenderer.render(data, opts);
        });
    },
    "./node_modules/qrcode/lib/can-promise.js"(module) {
        module.exports = function() {
            return typeof Promise === "function" && Promise.prototype && Promise.prototype.then;
        };
    },
    "./node_modules/qrcode/lib/core/alignment-pattern.js"(__unused_webpack_module, exports, __webpack_require__) {
        const getSymbolSize = __webpack_require__("./node_modules/qrcode/lib/core/utils.js").getSymbolSize;
        exports.getRowColCoords = function getRowColCoords(version) {
            if (version === 1) return [];
            const posCount = Math.floor(version / 7) + 2;
            const size = getSymbolSize(version);
            const intervals = size === 145 ? 26 : Math.ceil((size - 13) / (2 * posCount - 2)) * 2;
            const positions = [ size - 7 ];
            for (let i = 1; i < posCount - 1; i++) {
                positions[i] = positions[i - 1] - intervals;
            }
            positions.push(6);
            return positions.reverse();
        };
        exports.getPositions = function getPositions(version) {
            const coords = [];
            const pos = exports.getRowColCoords(version);
            const posLength = pos.length;
            for (let i = 0; i < posLength; i++) {
                for (let j = 0; j < posLength; j++) {
                    if (i === 0 && j === 0 || i === 0 && j === posLength - 1 || i === posLength - 1 && j === 0) {
                        continue;
                    }
                    coords.push([ pos[i], pos[j] ]);
                }
            }
            return coords;
        };
    },
    "./node_modules/qrcode/lib/core/alphanumeric-data.js"(module, __unused_webpack_exports, __webpack_require__) {
        const Mode = __webpack_require__("./node_modules/qrcode/lib/core/mode.js");
        const ALPHA_NUM_CHARS = [ "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", " ", "$", "%", "*", "+", "-", ".", "/", ":" ];
        function AlphanumericData(data) {
            this.mode = Mode.ALPHANUMERIC;
            this.data = data;
        }
        AlphanumericData.getBitsLength = function getBitsLength(length) {
            return 11 * Math.floor(length / 2) + 6 * (length % 2);
        };
        AlphanumericData.prototype.getLength = function getLength() {
            return this.data.length;
        };
        AlphanumericData.prototype.getBitsLength = function getBitsLength() {
            return AlphanumericData.getBitsLength(this.data.length);
        };
        AlphanumericData.prototype.write = function write(bitBuffer) {
            let i;
            for (i = 0; i + 2 <= this.data.length; i += 2) {
                let value = ALPHA_NUM_CHARS.indexOf(this.data[i]) * 45;
                value += ALPHA_NUM_CHARS.indexOf(this.data[i + 1]);
                bitBuffer.put(value, 11);
            }
            if (this.data.length % 2) {
                bitBuffer.put(ALPHA_NUM_CHARS.indexOf(this.data[i]), 6);
            }
        };
        module.exports = AlphanumericData;
    },
    "./node_modules/qrcode/lib/core/bit-buffer.js"(module) {
        function BitBuffer() {
            this.buffer = [];
            this.length = 0;
        }
        BitBuffer.prototype = {
            get: function(index) {
                const bufIndex = Math.floor(index / 8);
                return (this.buffer[bufIndex] >>> 7 - index % 8 & 1) === 1;
            },
            put: function(num, length) {
                for (let i = 0; i < length; i++) {
                    this.putBit((num >>> length - i - 1 & 1) === 1);
                }
            },
            getLengthInBits: function() {
                return this.length;
            },
            putBit: function(bit) {
                const bufIndex = Math.floor(this.length / 8);
                if (this.buffer.length <= bufIndex) {
                    this.buffer.push(0);
                }
                if (bit) {
                    this.buffer[bufIndex] |= 128 >>> this.length % 8;
                }
                this.length++;
            }
        };
        module.exports = BitBuffer;
    },
    "./node_modules/qrcode/lib/core/bit-matrix.js"(module) {
        function BitMatrix(size) {
            if (!size || size < 1) {
                throw new Error("BitMatrix size must be defined and greater than 0");
            }
            this.size = size;
            this.data = new Uint8Array(size * size);
            this.reservedBit = new Uint8Array(size * size);
        }
        BitMatrix.prototype.set = function(row, col, value, reserved) {
            const index = row * this.size + col;
            this.data[index] = value;
            if (reserved) this.reservedBit[index] = true;
        };
        BitMatrix.prototype.get = function(row, col) {
            return this.data[row * this.size + col];
        };
        BitMatrix.prototype.xor = function(row, col, value) {
            this.data[row * this.size + col] ^= value;
        };
        BitMatrix.prototype.isReserved = function(row, col) {
            return this.reservedBit[row * this.size + col];
        };
        module.exports = BitMatrix;
    },
    "./node_modules/qrcode/lib/core/byte-data.js"(module, __unused_webpack_exports, __webpack_require__) {
        const Mode = __webpack_require__("./node_modules/qrcode/lib/core/mode.js");
        function ByteData(data) {
            this.mode = Mode.BYTE;
            if (typeof data === "string") {
                this.data = (new TextEncoder).encode(data);
            } else {
                this.data = new Uint8Array(data);
            }
        }
        ByteData.getBitsLength = function getBitsLength(length) {
            return length * 8;
        };
        ByteData.prototype.getLength = function getLength() {
            return this.data.length;
        };
        ByteData.prototype.getBitsLength = function getBitsLength() {
            return ByteData.getBitsLength(this.data.length);
        };
        ByteData.prototype.write = function(bitBuffer) {
            for (let i = 0, l = this.data.length; i < l; i++) {
                bitBuffer.put(this.data[i], 8);
            }
        };
        module.exports = ByteData;
    },
    "./node_modules/qrcode/lib/core/error-correction-code.js"(__unused_webpack_module, exports, __webpack_require__) {
        const ECLevel = __webpack_require__("./node_modules/qrcode/lib/core/error-correction-level.js");
        const EC_BLOCKS_TABLE = [ 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 1, 2, 2, 4, 1, 2, 4, 4, 2, 4, 4, 4, 2, 4, 6, 5, 2, 4, 6, 6, 2, 5, 8, 8, 4, 5, 8, 8, 4, 5, 8, 11, 4, 8, 10, 11, 4, 9, 12, 16, 4, 9, 16, 16, 6, 10, 12, 18, 6, 10, 17, 16, 6, 11, 16, 19, 6, 13, 18, 21, 7, 14, 21, 25, 8, 16, 20, 25, 8, 17, 23, 25, 9, 17, 23, 34, 9, 18, 25, 30, 10, 20, 27, 32, 12, 21, 29, 35, 12, 23, 34, 37, 12, 25, 34, 40, 13, 26, 35, 42, 14, 28, 38, 45, 15, 29, 40, 48, 16, 31, 43, 51, 17, 33, 45, 54, 18, 35, 48, 57, 19, 37, 51, 60, 19, 38, 53, 63, 20, 40, 56, 66, 21, 43, 59, 70, 22, 45, 62, 74, 24, 47, 65, 77, 25, 49, 68, 81 ];
        const EC_CODEWORDS_TABLE = [ 7, 10, 13, 17, 10, 16, 22, 28, 15, 26, 36, 44, 20, 36, 52, 64, 26, 48, 72, 88, 36, 64, 96, 112, 40, 72, 108, 130, 48, 88, 132, 156, 60, 110, 160, 192, 72, 130, 192, 224, 80, 150, 224, 264, 96, 176, 260, 308, 104, 198, 288, 352, 120, 216, 320, 384, 132, 240, 360, 432, 144, 280, 408, 480, 168, 308, 448, 532, 180, 338, 504, 588, 196, 364, 546, 650, 224, 416, 600, 700, 224, 442, 644, 750, 252, 476, 690, 816, 270, 504, 750, 900, 300, 560, 810, 960, 312, 588, 870, 1050, 336, 644, 952, 1110, 360, 700, 1020, 1200, 390, 728, 1050, 1260, 420, 784, 1140, 1350, 450, 812, 1200, 1440, 480, 868, 1290, 1530, 510, 924, 1350, 1620, 540, 980, 1440, 1710, 570, 1036, 1530, 1800, 570, 1064, 1590, 1890, 600, 1120, 1680, 1980, 630, 1204, 1770, 2100, 660, 1260, 1860, 2220, 720, 1316, 1950, 2310, 750, 1372, 2040, 2430 ];
        exports.getBlocksCount = function getBlocksCount(version, errorCorrectionLevel) {
            switch (errorCorrectionLevel) {
              case ECLevel.L:
                return EC_BLOCKS_TABLE[(version - 1) * 4 + 0];

              case ECLevel.M:
                return EC_BLOCKS_TABLE[(version - 1) * 4 + 1];

              case ECLevel.Q:
                return EC_BLOCKS_TABLE[(version - 1) * 4 + 2];

              case ECLevel.H:
                return EC_BLOCKS_TABLE[(version - 1) * 4 + 3];

              default:
                return undefined;
            }
        };
        exports.getTotalCodewordsCount = function getTotalCodewordsCount(version, errorCorrectionLevel) {
            switch (errorCorrectionLevel) {
              case ECLevel.L:
                return EC_CODEWORDS_TABLE[(version - 1) * 4 + 0];

              case ECLevel.M:
                return EC_CODEWORDS_TABLE[(version - 1) * 4 + 1];

              case ECLevel.Q:
                return EC_CODEWORDS_TABLE[(version - 1) * 4 + 2];

              case ECLevel.H:
                return EC_CODEWORDS_TABLE[(version - 1) * 4 + 3];

              default:
                return undefined;
            }
        };
    },
    "./node_modules/qrcode/lib/core/error-correction-level.js"(__unused_webpack_module, exports) {
        exports.L = {
            bit: 1
        };
        exports.M = {
            bit: 0
        };
        exports.Q = {
            bit: 3
        };
        exports.H = {
            bit: 2
        };
        function fromString(string) {
            if (typeof string !== "string") {
                throw new Error("Param is not a string");
            }
            const lcStr = string.toLowerCase();
            switch (lcStr) {
              case "l":
              case "low":
                return exports.L;

              case "m":
              case "medium":
                return exports.M;

              case "q":
              case "quartile":
                return exports.Q;

              case "h":
              case "high":
                return exports.H;

              default:
                throw new Error("Unknown EC Level: " + string);
            }
        }
        exports.isValid = function isValid(level) {
            return level && typeof level.bit !== "undefined" && level.bit >= 0 && level.bit < 4;
        };
        exports.from = function from(value, defaultValue) {
            if (exports.isValid(value)) {
                return value;
            }
            try {
                return fromString(value);
            } catch (e) {
                return defaultValue;
            }
        };
    },
    "./node_modules/qrcode/lib/core/finder-pattern.js"(__unused_webpack_module, exports, __webpack_require__) {
        const getSymbolSize = __webpack_require__("./node_modules/qrcode/lib/core/utils.js").getSymbolSize;
        const FINDER_PATTERN_SIZE = 7;
        exports.getPositions = function getPositions(version) {
            const size = getSymbolSize(version);
            return [ [ 0, 0 ], [ size - FINDER_PATTERN_SIZE, 0 ], [ 0, size - FINDER_PATTERN_SIZE ] ];
        };
    },
    "./node_modules/qrcode/lib/core/format-info.js"(__unused_webpack_module, exports, __webpack_require__) {
        const Utils = __webpack_require__("./node_modules/qrcode/lib/core/utils.js");
        const G15 = 1 << 10 | 1 << 8 | 1 << 5 | 1 << 4 | 1 << 2 | 1 << 1 | 1 << 0;
        const G15_MASK = 1 << 14 | 1 << 12 | 1 << 10 | 1 << 4 | 1 << 1;
        const G15_BCH = Utils.getBCHDigit(G15);
        exports.getEncodedBits = function getEncodedBits(errorCorrectionLevel, mask) {
            const data = errorCorrectionLevel.bit << 3 | mask;
            let d = data << 10;
            while (Utils.getBCHDigit(d) - G15_BCH >= 0) {
                d ^= G15 << Utils.getBCHDigit(d) - G15_BCH;
            }
            return (data << 10 | d) ^ G15_MASK;
        };
    },
    "./node_modules/qrcode/lib/core/galois-field.js"(__unused_webpack_module, exports) {
        const EXP_TABLE = new Uint8Array(512);
        const LOG_TABLE = new Uint8Array(256);
        (function initTables() {
            let x = 1;
            for (let i = 0; i < 255; i++) {
                EXP_TABLE[i] = x;
                LOG_TABLE[x] = i;
                x <<= 1;
                if (x & 256) {
                    x ^= 285;
                }
            }
            for (let i = 255; i < 512; i++) {
                EXP_TABLE[i] = EXP_TABLE[i - 255];
            }
        })();
        exports.log = function log(n) {
            if (n < 1) throw new Error("log(" + n + ")");
            return LOG_TABLE[n];
        };
        exports.exp = function exp(n) {
            return EXP_TABLE[n];
        };
        exports.mul = function mul(x, y) {
            if (x === 0 || y === 0) return 0;
            return EXP_TABLE[LOG_TABLE[x] + LOG_TABLE[y]];
        };
    },
    "./node_modules/qrcode/lib/core/kanji-data.js"(module, __unused_webpack_exports, __webpack_require__) {
        const Mode = __webpack_require__("./node_modules/qrcode/lib/core/mode.js");
        const Utils = __webpack_require__("./node_modules/qrcode/lib/core/utils.js");
        function KanjiData(data) {
            this.mode = Mode.KANJI;
            this.data = data;
        }
        KanjiData.getBitsLength = function getBitsLength(length) {
            return length * 13;
        };
        KanjiData.prototype.getLength = function getLength() {
            return this.data.length;
        };
        KanjiData.prototype.getBitsLength = function getBitsLength() {
            return KanjiData.getBitsLength(this.data.length);
        };
        KanjiData.prototype.write = function(bitBuffer) {
            let i;
            for (i = 0; i < this.data.length; i++) {
                let value = Utils.toSJIS(this.data[i]);
                if (value >= 33088 && value <= 40956) {
                    value -= 33088;
                } else if (value >= 57408 && value <= 60351) {
                    value -= 49472;
                } else {
                    throw new Error("Invalid SJIS character: " + this.data[i] + "\n" + "Make sure your charset is UTF-8");
                }
                value = (value >>> 8 & 255) * 192 + (value & 255);
                bitBuffer.put(value, 13);
            }
        };
        module.exports = KanjiData;
    },
    "./node_modules/qrcode/lib/core/mask-pattern.js"(__unused_webpack_module, exports) {
        exports.Patterns = {
            PATTERN000: 0,
            PATTERN001: 1,
            PATTERN010: 2,
            PATTERN011: 3,
            PATTERN100: 4,
            PATTERN101: 5,
            PATTERN110: 6,
            PATTERN111: 7
        };
        const PenaltyScores = {
            N1: 3,
            N2: 3,
            N3: 40,
            N4: 10
        };
        exports.isValid = function isValid(mask) {
            return mask != null && mask !== "" && !isNaN(mask) && mask >= 0 && mask <= 7;
        };
        exports.from = function from(value) {
            return exports.isValid(value) ? parseInt(value, 10) : undefined;
        };
        exports.getPenaltyN1 = function getPenaltyN1(data) {
            const size = data.size;
            let points = 0;
            let sameCountCol = 0;
            let sameCountRow = 0;
            let lastCol = null;
            let lastRow = null;
            for (let row = 0; row < size; row++) {
                sameCountCol = sameCountRow = 0;
                lastCol = lastRow = null;
                for (let col = 0; col < size; col++) {
                    let module = data.get(row, col);
                    if (module === lastCol) {
                        sameCountCol++;
                    } else {
                        if (sameCountCol >= 5) points += PenaltyScores.N1 + (sameCountCol - 5);
                        lastCol = module;
                        sameCountCol = 1;
                    }
                    module = data.get(col, row);
                    if (module === lastRow) {
                        sameCountRow++;
                    } else {
                        if (sameCountRow >= 5) points += PenaltyScores.N1 + (sameCountRow - 5);
                        lastRow = module;
                        sameCountRow = 1;
                    }
                }
                if (sameCountCol >= 5) points += PenaltyScores.N1 + (sameCountCol - 5);
                if (sameCountRow >= 5) points += PenaltyScores.N1 + (sameCountRow - 5);
            }
            return points;
        };
        exports.getPenaltyN2 = function getPenaltyN2(data) {
            const size = data.size;
            let points = 0;
            for (let row = 0; row < size - 1; row++) {
                for (let col = 0; col < size - 1; col++) {
                    const last = data.get(row, col) + data.get(row, col + 1) + data.get(row + 1, col) + data.get(row + 1, col + 1);
                    if (last === 4 || last === 0) points++;
                }
            }
            return points * PenaltyScores.N2;
        };
        exports.getPenaltyN3 = function getPenaltyN3(data) {
            const size = data.size;
            let points = 0;
            let bitsCol = 0;
            let bitsRow = 0;
            for (let row = 0; row < size; row++) {
                bitsCol = bitsRow = 0;
                for (let col = 0; col < size; col++) {
                    bitsCol = bitsCol << 1 & 2047 | data.get(row, col);
                    if (col >= 10 && (bitsCol === 1488 || bitsCol === 93)) points++;
                    bitsRow = bitsRow << 1 & 2047 | data.get(col, row);
                    if (col >= 10 && (bitsRow === 1488 || bitsRow === 93)) points++;
                }
            }
            return points * PenaltyScores.N3;
        };
        exports.getPenaltyN4 = function getPenaltyN4(data) {
            let darkCount = 0;
            const modulesCount = data.data.length;
            for (let i = 0; i < modulesCount; i++) darkCount += data.data[i];
            const k = Math.abs(Math.ceil(darkCount * 100 / modulesCount / 5) - 10);
            return k * PenaltyScores.N4;
        };
        function getMaskAt(maskPattern, i, j) {
            switch (maskPattern) {
              case exports.Patterns.PATTERN000:
                return (i + j) % 2 === 0;

              case exports.Patterns.PATTERN001:
                return i % 2 === 0;

              case exports.Patterns.PATTERN010:
                return j % 3 === 0;

              case exports.Patterns.PATTERN011:
                return (i + j) % 3 === 0;

              case exports.Patterns.PATTERN100:
                return (Math.floor(i / 2) + Math.floor(j / 3)) % 2 === 0;

              case exports.Patterns.PATTERN101:
                return i * j % 2 + i * j % 3 === 0;

              case exports.Patterns.PATTERN110:
                return (i * j % 2 + i * j % 3) % 2 === 0;

              case exports.Patterns.PATTERN111:
                return (i * j % 3 + (i + j) % 2) % 2 === 0;

              default:
                throw new Error("bad maskPattern:" + maskPattern);
            }
        }
        exports.applyMask = function applyMask(pattern, data) {
            const size = data.size;
            for (let col = 0; col < size; col++) {
                for (let row = 0; row < size; row++) {
                    if (data.isReserved(row, col)) continue;
                    data.xor(row, col, getMaskAt(pattern, row, col));
                }
            }
        };
        exports.getBestMask = function getBestMask(data, setupFormatFunc) {
            const numPatterns = Object.keys(exports.Patterns).length;
            let bestPattern = 0;
            let lowerPenalty = Infinity;
            for (let p = 0; p < numPatterns; p++) {
                setupFormatFunc(p);
                exports.applyMask(p, data);
                const penalty = exports.getPenaltyN1(data) + exports.getPenaltyN2(data) + exports.getPenaltyN3(data) + exports.getPenaltyN4(data);
                exports.applyMask(p, data);
                if (penalty < lowerPenalty) {
                    lowerPenalty = penalty;
                    bestPattern = p;
                }
            }
            return bestPattern;
        };
    },
    "./node_modules/qrcode/lib/core/mode.js"(__unused_webpack_module, exports, __webpack_require__) {
        const VersionCheck = __webpack_require__("./node_modules/qrcode/lib/core/version-check.js");
        const Regex = __webpack_require__("./node_modules/qrcode/lib/core/regex.js");
        exports.NUMERIC = {
            id: "Numeric",
            bit: 1 << 0,
            ccBits: [ 10, 12, 14 ]
        };
        exports.ALPHANUMERIC = {
            id: "Alphanumeric",
            bit: 1 << 1,
            ccBits: [ 9, 11, 13 ]
        };
        exports.BYTE = {
            id: "Byte",
            bit: 1 << 2,
            ccBits: [ 8, 16, 16 ]
        };
        exports.KANJI = {
            id: "Kanji",
            bit: 1 << 3,
            ccBits: [ 8, 10, 12 ]
        };
        exports.MIXED = {
            bit: -1
        };
        exports.getCharCountIndicator = function getCharCountIndicator(mode, version) {
            if (!mode.ccBits) throw new Error("Invalid mode: " + mode);
            if (!VersionCheck.isValid(version)) {
                throw new Error("Invalid version: " + version);
            }
            if (version >= 1 && version < 10) return mode.ccBits[0]; else if (version < 27) return mode.ccBits[1];
            return mode.ccBits[2];
        };
        exports.getBestModeForData = function getBestModeForData(dataStr) {
            if (Regex.testNumeric(dataStr)) return exports.NUMERIC; else if (Regex.testAlphanumeric(dataStr)) return exports.ALPHANUMERIC; else if (Regex.testKanji(dataStr)) return exports.KANJI; else return exports.BYTE;
        };
        exports.toString = function toString(mode) {
            if (mode && mode.id) return mode.id;
            throw new Error("Invalid mode");
        };
        exports.isValid = function isValid(mode) {
            return mode && mode.bit && mode.ccBits;
        };
        function fromString(string) {
            if (typeof string !== "string") {
                throw new Error("Param is not a string");
            }
            const lcStr = string.toLowerCase();
            switch (lcStr) {
              case "numeric":
                return exports.NUMERIC;

              case "alphanumeric":
                return exports.ALPHANUMERIC;

              case "kanji":
                return exports.KANJI;

              case "byte":
                return exports.BYTE;

              default:
                throw new Error("Unknown mode: " + string);
            }
        }
        exports.from = function from(value, defaultValue) {
            if (exports.isValid(value)) {
                return value;
            }
            try {
                return fromString(value);
            } catch (e) {
                return defaultValue;
            }
        };
    },
    "./node_modules/qrcode/lib/core/numeric-data.js"(module, __unused_webpack_exports, __webpack_require__) {
        const Mode = __webpack_require__("./node_modules/qrcode/lib/core/mode.js");
        function NumericData(data) {
            this.mode = Mode.NUMERIC;
            this.data = data.toString();
        }
        NumericData.getBitsLength = function getBitsLength(length) {
            return 10 * Math.floor(length / 3) + (length % 3 ? length % 3 * 3 + 1 : 0);
        };
        NumericData.prototype.getLength = function getLength() {
            return this.data.length;
        };
        NumericData.prototype.getBitsLength = function getBitsLength() {
            return NumericData.getBitsLength(this.data.length);
        };
        NumericData.prototype.write = function write(bitBuffer) {
            let i, group, value;
            for (i = 0; i + 3 <= this.data.length; i += 3) {
                group = this.data.substr(i, 3);
                value = parseInt(group, 10);
                bitBuffer.put(value, 10);
            }
            const remainingNum = this.data.length - i;
            if (remainingNum > 0) {
                group = this.data.substr(i);
                value = parseInt(group, 10);
                bitBuffer.put(value, remainingNum * 3 + 1);
            }
        };
        module.exports = NumericData;
    },
    "./node_modules/qrcode/lib/core/polynomial.js"(__unused_webpack_module, exports, __webpack_require__) {
        const GF = __webpack_require__("./node_modules/qrcode/lib/core/galois-field.js");
        exports.mul = function mul(p1, p2) {
            const coeff = new Uint8Array(p1.length + p2.length - 1);
            for (let i = 0; i < p1.length; i++) {
                for (let j = 0; j < p2.length; j++) {
                    coeff[i + j] ^= GF.mul(p1[i], p2[j]);
                }
            }
            return coeff;
        };
        exports.mod = function mod(divident, divisor) {
            let result = new Uint8Array(divident);
            while (result.length - divisor.length >= 0) {
                const coeff = result[0];
                for (let i = 0; i < divisor.length; i++) {
                    result[i] ^= GF.mul(divisor[i], coeff);
                }
                let offset = 0;
                while (offset < result.length && result[offset] === 0) offset++;
                result = result.slice(offset);
            }
            return result;
        };
        exports.generateECPolynomial = function generateECPolynomial(degree) {
            let poly = new Uint8Array([ 1 ]);
            for (let i = 0; i < degree; i++) {
                poly = exports.mul(poly, new Uint8Array([ 1, GF.exp(i) ]));
            }
            return poly;
        };
    },
    "./node_modules/qrcode/lib/core/qrcode.js"(__unused_webpack_module, exports, __webpack_require__) {
        const Utils = __webpack_require__("./node_modules/qrcode/lib/core/utils.js");
        const ECLevel = __webpack_require__("./node_modules/qrcode/lib/core/error-correction-level.js");
        const BitBuffer = __webpack_require__("./node_modules/qrcode/lib/core/bit-buffer.js");
        const BitMatrix = __webpack_require__("./node_modules/qrcode/lib/core/bit-matrix.js");
        const AlignmentPattern = __webpack_require__("./node_modules/qrcode/lib/core/alignment-pattern.js");
        const FinderPattern = __webpack_require__("./node_modules/qrcode/lib/core/finder-pattern.js");
        const MaskPattern = __webpack_require__("./node_modules/qrcode/lib/core/mask-pattern.js");
        const ECCode = __webpack_require__("./node_modules/qrcode/lib/core/error-correction-code.js");
        const ReedSolomonEncoder = __webpack_require__("./node_modules/qrcode/lib/core/reed-solomon-encoder.js");
        const Version = __webpack_require__("./node_modules/qrcode/lib/core/version.js");
        const FormatInfo = __webpack_require__("./node_modules/qrcode/lib/core/format-info.js");
        const Mode = __webpack_require__("./node_modules/qrcode/lib/core/mode.js");
        const Segments = __webpack_require__("./node_modules/qrcode/lib/core/segments.js");
        function setupFinderPattern(matrix, version) {
            const size = matrix.size;
            const pos = FinderPattern.getPositions(version);
            for (let i = 0; i < pos.length; i++) {
                const row = pos[i][0];
                const col = pos[i][1];
                for (let r = -1; r <= 7; r++) {
                    if (row + r <= -1 || size <= row + r) continue;
                    for (let c = -1; c <= 7; c++) {
                        if (col + c <= -1 || size <= col + c) continue;
                        if (r >= 0 && r <= 6 && (c === 0 || c === 6) || c >= 0 && c <= 6 && (r === 0 || r === 6) || r >= 2 && r <= 4 && c >= 2 && c <= 4) {
                            matrix.set(row + r, col + c, true, true);
                        } else {
                            matrix.set(row + r, col + c, false, true);
                        }
                    }
                }
            }
        }
        function setupTimingPattern(matrix) {
            const size = matrix.size;
            for (let r = 8; r < size - 8; r++) {
                const value = r % 2 === 0;
                matrix.set(r, 6, value, true);
                matrix.set(6, r, value, true);
            }
        }
        function setupAlignmentPattern(matrix, version) {
            const pos = AlignmentPattern.getPositions(version);
            for (let i = 0; i < pos.length; i++) {
                const row = pos[i][0];
                const col = pos[i][1];
                for (let r = -2; r <= 2; r++) {
                    for (let c = -2; c <= 2; c++) {
                        if (r === -2 || r === 2 || c === -2 || c === 2 || r === 0 && c === 0) {
                            matrix.set(row + r, col + c, true, true);
                        } else {
                            matrix.set(row + r, col + c, false, true);
                        }
                    }
                }
            }
        }
        function setupVersionInfo(matrix, version) {
            const size = matrix.size;
            const bits = Version.getEncodedBits(version);
            let row, col, mod;
            for (let i = 0; i < 18; i++) {
                row = Math.floor(i / 3);
                col = i % 3 + size - 8 - 3;
                mod = (bits >> i & 1) === 1;
                matrix.set(row, col, mod, true);
                matrix.set(col, row, mod, true);
            }
        }
        function setupFormatInfo(matrix, errorCorrectionLevel, maskPattern) {
            const size = matrix.size;
            const bits = FormatInfo.getEncodedBits(errorCorrectionLevel, maskPattern);
            let i, mod;
            for (i = 0; i < 15; i++) {
                mod = (bits >> i & 1) === 1;
                if (i < 6) {
                    matrix.set(i, 8, mod, true);
                } else if (i < 8) {
                    matrix.set(i + 1, 8, mod, true);
                } else {
                    matrix.set(size - 15 + i, 8, mod, true);
                }
                if (i < 8) {
                    matrix.set(8, size - i - 1, mod, true);
                } else if (i < 9) {
                    matrix.set(8, 15 - i - 1 + 1, mod, true);
                } else {
                    matrix.set(8, 15 - i - 1, mod, true);
                }
            }
            matrix.set(size - 8, 8, 1, true);
        }
        function setupData(matrix, data) {
            const size = matrix.size;
            let inc = -1;
            let row = size - 1;
            let bitIndex = 7;
            let byteIndex = 0;
            for (let col = size - 1; col > 0; col -= 2) {
                if (col === 6) col--;
                while (true) {
                    for (let c = 0; c < 2; c++) {
                        if (!matrix.isReserved(row, col - c)) {
                            let dark = false;
                            if (byteIndex < data.length) {
                                dark = (data[byteIndex] >>> bitIndex & 1) === 1;
                            }
                            matrix.set(row, col - c, dark);
                            bitIndex--;
                            if (bitIndex === -1) {
                                byteIndex++;
                                bitIndex = 7;
                            }
                        }
                    }
                    row += inc;
                    if (row < 0 || size <= row) {
                        row -= inc;
                        inc = -inc;
                        break;
                    }
                }
            }
        }
        function createData(version, errorCorrectionLevel, segments) {
            const buffer = new BitBuffer;
            segments.forEach(function(data) {
                buffer.put(data.mode.bit, 4);
                buffer.put(data.getLength(), Mode.getCharCountIndicator(data.mode, version));
                data.write(buffer);
            });
            const totalCodewords = Utils.getSymbolTotalCodewords(version);
            const ecTotalCodewords = ECCode.getTotalCodewordsCount(version, errorCorrectionLevel);
            const dataTotalCodewordsBits = (totalCodewords - ecTotalCodewords) * 8;
            if (buffer.getLengthInBits() + 4 <= dataTotalCodewordsBits) {
                buffer.put(0, 4);
            }
            while (buffer.getLengthInBits() % 8 !== 0) {
                buffer.putBit(0);
            }
            const remainingByte = (dataTotalCodewordsBits - buffer.getLengthInBits()) / 8;
            for (let i = 0; i < remainingByte; i++) {
                buffer.put(i % 2 ? 17 : 236, 8);
            }
            return createCodewords(buffer, version, errorCorrectionLevel);
        }
        function createCodewords(bitBuffer, version, errorCorrectionLevel) {
            const totalCodewords = Utils.getSymbolTotalCodewords(version);
            const ecTotalCodewords = ECCode.getTotalCodewordsCount(version, errorCorrectionLevel);
            const dataTotalCodewords = totalCodewords - ecTotalCodewords;
            const ecTotalBlocks = ECCode.getBlocksCount(version, errorCorrectionLevel);
            const blocksInGroup2 = totalCodewords % ecTotalBlocks;
            const blocksInGroup1 = ecTotalBlocks - blocksInGroup2;
            const totalCodewordsInGroup1 = Math.floor(totalCodewords / ecTotalBlocks);
            const dataCodewordsInGroup1 = Math.floor(dataTotalCodewords / ecTotalBlocks);
            const dataCodewordsInGroup2 = dataCodewordsInGroup1 + 1;
            const ecCount = totalCodewordsInGroup1 - dataCodewordsInGroup1;
            const rs = new ReedSolomonEncoder(ecCount);
            let offset = 0;
            const dcData = new Array(ecTotalBlocks);
            const ecData = new Array(ecTotalBlocks);
            let maxDataSize = 0;
            const buffer = new Uint8Array(bitBuffer.buffer);
            for (let b = 0; b < ecTotalBlocks; b++) {
                const dataSize = b < blocksInGroup1 ? dataCodewordsInGroup1 : dataCodewordsInGroup2;
                dcData[b] = buffer.slice(offset, offset + dataSize);
                ecData[b] = rs.encode(dcData[b]);
                offset += dataSize;
                maxDataSize = Math.max(maxDataSize, dataSize);
            }
            const data = new Uint8Array(totalCodewords);
            let index = 0;
            let i, r;
            for (i = 0; i < maxDataSize; i++) {
                for (r = 0; r < ecTotalBlocks; r++) {
                    if (i < dcData[r].length) {
                        data[index++] = dcData[r][i];
                    }
                }
            }
            for (i = 0; i < ecCount; i++) {
                for (r = 0; r < ecTotalBlocks; r++) {
                    data[index++] = ecData[r][i];
                }
            }
            return data;
        }
        function createSymbol(data, version, errorCorrectionLevel, maskPattern) {
            let segments;
            if (Array.isArray(data)) {
                segments = Segments.fromArray(data);
            } else if (typeof data === "string") {
                let estimatedVersion = version;
                if (!estimatedVersion) {
                    const rawSegments = Segments.rawSplit(data);
                    estimatedVersion = Version.getBestVersionForData(rawSegments, errorCorrectionLevel);
                }
                segments = Segments.fromString(data, estimatedVersion || 40);
            } else {
                throw new Error("Invalid data");
            }
            const bestVersion = Version.getBestVersionForData(segments, errorCorrectionLevel);
            if (!bestVersion) {
                throw new Error("The amount of data is too big to be stored in a QR Code");
            }
            if (!version) {
                version = bestVersion;
            } else if (version < bestVersion) {
                throw new Error("\n" + "The chosen QR Code version cannot contain this amount of data.\n" + "Minimum version required to store current data is: " + bestVersion + ".\n");
            }
            const dataBits = createData(version, errorCorrectionLevel, segments);
            const moduleCount = Utils.getSymbolSize(version);
            const modules = new BitMatrix(moduleCount);
            setupFinderPattern(modules, version);
            setupTimingPattern(modules);
            setupAlignmentPattern(modules, version);
            setupFormatInfo(modules, errorCorrectionLevel, 0);
            if (version >= 7) {
                setupVersionInfo(modules, version);
            }
            setupData(modules, dataBits);
            if (isNaN(maskPattern)) {
                maskPattern = MaskPattern.getBestMask(modules, setupFormatInfo.bind(null, modules, errorCorrectionLevel));
            }
            MaskPattern.applyMask(maskPattern, modules);
            setupFormatInfo(modules, errorCorrectionLevel, maskPattern);
            return {
                modules,
                version,
                errorCorrectionLevel,
                maskPattern,
                segments
            };
        }
        exports.create = function create(data, options) {
            if (typeof data === "undefined" || data === "") {
                throw new Error("No input text");
            }
            let errorCorrectionLevel = ECLevel.M;
            let version;
            let mask;
            if (typeof options !== "undefined") {
                errorCorrectionLevel = ECLevel.from(options.errorCorrectionLevel, ECLevel.M);
                version = Version.from(options.version);
                mask = MaskPattern.from(options.maskPattern);
                if (options.toSJISFunc) {
                    Utils.setToSJISFunction(options.toSJISFunc);
                }
            }
            return createSymbol(data, version, errorCorrectionLevel, mask);
        };
    },
    "./node_modules/qrcode/lib/core/reed-solomon-encoder.js"(module, __unused_webpack_exports, __webpack_require__) {
        const Polynomial = __webpack_require__("./node_modules/qrcode/lib/core/polynomial.js");
        function ReedSolomonEncoder(degree) {
            this.genPoly = undefined;
            this.degree = degree;
            if (this.degree) this.initialize(this.degree);
        }
        ReedSolomonEncoder.prototype.initialize = function initialize(degree) {
            this.degree = degree;
            this.genPoly = Polynomial.generateECPolynomial(this.degree);
        };
        ReedSolomonEncoder.prototype.encode = function encode(data) {
            if (!this.genPoly) {
                throw new Error("Encoder not initialized");
            }
            const paddedData = new Uint8Array(data.length + this.degree);
            paddedData.set(data);
            const remainder = Polynomial.mod(paddedData, this.genPoly);
            const start = this.degree - remainder.length;
            if (start > 0) {
                const buff = new Uint8Array(this.degree);
                buff.set(remainder, start);
                return buff;
            }
            return remainder;
        };
        module.exports = ReedSolomonEncoder;
    },
    "./node_modules/qrcode/lib/core/regex.js"(__unused_webpack_module, exports) {
        const numeric = "[0-9]+";
        const alphanumeric = "[A-Z $%*+\\-./:]+";
        let kanji = "(?:[u3000-u303F]|[u3040-u309F]|[u30A0-u30FF]|" + "[uFF00-uFFEF]|[u4E00-u9FAF]|[u2605-u2606]|[u2190-u2195]|u203B|" + "[u2010u2015u2018u2019u2025u2026u201Cu201Du2225u2260]|" + "[u0391-u0451]|[u00A7u00A8u00B1u00B4u00D7u00F7])+";
        kanji = kanji.replace(/u/g, "\\u");
        const byte = "(?:(?![A-Z0-9 $%*+\\-./:]|" + kanji + ")(?:.|[\r\n]))+";
        exports.KANJI = new RegExp(kanji, "g");
        exports.BYTE_KANJI = new RegExp("[^A-Z0-9 $%*+\\-./:]+", "g");
        exports.BYTE = new RegExp(byte, "g");
        exports.NUMERIC = new RegExp(numeric, "g");
        exports.ALPHANUMERIC = new RegExp(alphanumeric, "g");
        const TEST_KANJI = new RegExp("^" + kanji + "$");
        const TEST_NUMERIC = new RegExp("^" + numeric + "$");
        const TEST_ALPHANUMERIC = new RegExp("^[A-Z0-9 $%*+\\-./:]+$");
        exports.testKanji = function testKanji(str) {
            return TEST_KANJI.test(str);
        };
        exports.testNumeric = function testNumeric(str) {
            return TEST_NUMERIC.test(str);
        };
        exports.testAlphanumeric = function testAlphanumeric(str) {
            return TEST_ALPHANUMERIC.test(str);
        };
    },
    "./node_modules/qrcode/lib/core/segments.js"(__unused_webpack_module, exports, __webpack_require__) {
        const Mode = __webpack_require__("./node_modules/qrcode/lib/core/mode.js");
        const NumericData = __webpack_require__("./node_modules/qrcode/lib/core/numeric-data.js");
        const AlphanumericData = __webpack_require__("./node_modules/qrcode/lib/core/alphanumeric-data.js");
        const ByteData = __webpack_require__("./node_modules/qrcode/lib/core/byte-data.js");
        const KanjiData = __webpack_require__("./node_modules/qrcode/lib/core/kanji-data.js");
        const Regex = __webpack_require__("./node_modules/qrcode/lib/core/regex.js");
        const Utils = __webpack_require__("./node_modules/qrcode/lib/core/utils.js");
        const dijkstra = __webpack_require__("./node_modules/dijkstrajs/dijkstra.js");
        function getStringByteLength(str) {
            return unescape(encodeURIComponent(str)).length;
        }
        function getSegments(regex, mode, str) {
            const segments = [];
            let result;
            while ((result = regex.exec(str)) !== null) {
                segments.push({
                    data: result[0],
                    index: result.index,
                    mode,
                    length: result[0].length
                });
            }
            return segments;
        }
        function getSegmentsFromString(dataStr) {
            const numSegs = getSegments(Regex.NUMERIC, Mode.NUMERIC, dataStr);
            const alphaNumSegs = getSegments(Regex.ALPHANUMERIC, Mode.ALPHANUMERIC, dataStr);
            let byteSegs;
            let kanjiSegs;
            if (Utils.isKanjiModeEnabled()) {
                byteSegs = getSegments(Regex.BYTE, Mode.BYTE, dataStr);
                kanjiSegs = getSegments(Regex.KANJI, Mode.KANJI, dataStr);
            } else {
                byteSegs = getSegments(Regex.BYTE_KANJI, Mode.BYTE, dataStr);
                kanjiSegs = [];
            }
            const segs = numSegs.concat(alphaNumSegs, byteSegs, kanjiSegs);
            return segs.sort(function(s1, s2) {
                return s1.index - s2.index;
            }).map(function(obj) {
                return {
                    data: obj.data,
                    mode: obj.mode,
                    length: obj.length
                };
            });
        }
        function getSegmentBitsLength(length, mode) {
            switch (mode) {
              case Mode.NUMERIC:
                return NumericData.getBitsLength(length);

              case Mode.ALPHANUMERIC:
                return AlphanumericData.getBitsLength(length);

              case Mode.KANJI:
                return KanjiData.getBitsLength(length);

              case Mode.BYTE:
                return ByteData.getBitsLength(length);
            }
        }
        function mergeSegments(segs) {
            return segs.reduce(function(acc, curr) {
                const prevSeg = acc.length - 1 >= 0 ? acc[acc.length - 1] : null;
                if (prevSeg && prevSeg.mode === curr.mode) {
                    acc[acc.length - 1].data += curr.data;
                    return acc;
                }
                acc.push(curr);
                return acc;
            }, []);
        }
        function buildNodes(segs) {
            const nodes = [];
            for (let i = 0; i < segs.length; i++) {
                const seg = segs[i];
                switch (seg.mode) {
                  case Mode.NUMERIC:
                    nodes.push([ seg, {
                        data: seg.data,
                        mode: Mode.ALPHANUMERIC,
                        length: seg.length
                    }, {
                        data: seg.data,
                        mode: Mode.BYTE,
                        length: seg.length
                    } ]);
                    break;

                  case Mode.ALPHANUMERIC:
                    nodes.push([ seg, {
                        data: seg.data,
                        mode: Mode.BYTE,
                        length: seg.length
                    } ]);
                    break;

                  case Mode.KANJI:
                    nodes.push([ seg, {
                        data: seg.data,
                        mode: Mode.BYTE,
                        length: getStringByteLength(seg.data)
                    } ]);
                    break;

                  case Mode.BYTE:
                    nodes.push([ {
                        data: seg.data,
                        mode: Mode.BYTE,
                        length: getStringByteLength(seg.data)
                    } ]);
                }
            }
            return nodes;
        }
        function buildGraph(nodes, version) {
            const table = {};
            const graph = {
                start: {}
            };
            let prevNodeIds = [ "start" ];
            for (let i = 0; i < nodes.length; i++) {
                const nodeGroup = nodes[i];
                const currentNodeIds = [];
                for (let j = 0; j < nodeGroup.length; j++) {
                    const node = nodeGroup[j];
                    const key = "" + i + j;
                    currentNodeIds.push(key);
                    table[key] = {
                        node,
                        lastCount: 0
                    };
                    graph[key] = {};
                    for (let n = 0; n < prevNodeIds.length; n++) {
                        const prevNodeId = prevNodeIds[n];
                        if (table[prevNodeId] && table[prevNodeId].node.mode === node.mode) {
                            graph[prevNodeId][key] = getSegmentBitsLength(table[prevNodeId].lastCount + node.length, node.mode) - getSegmentBitsLength(table[prevNodeId].lastCount, node.mode);
                            table[prevNodeId].lastCount += node.length;
                        } else {
                            if (table[prevNodeId]) table[prevNodeId].lastCount = node.length;
                            graph[prevNodeId][key] = getSegmentBitsLength(node.length, node.mode) + 4 + Mode.getCharCountIndicator(node.mode, version);
                        }
                    }
                }
                prevNodeIds = currentNodeIds;
            }
            for (let n = 0; n < prevNodeIds.length; n++) {
                graph[prevNodeIds[n]].end = 0;
            }
            return {
                map: graph,
                table
            };
        }
        function buildSingleSegment(data, modesHint) {
            let mode;
            const bestMode = Mode.getBestModeForData(data);
            mode = Mode.from(modesHint, bestMode);
            if (mode !== Mode.BYTE && mode.bit < bestMode.bit) {
                throw new Error('"' + data + '"' + " cannot be encoded with mode " + Mode.toString(mode) + ".\n Suggested mode is: " + Mode.toString(bestMode));
            }
            if (mode === Mode.KANJI && !Utils.isKanjiModeEnabled()) {
                mode = Mode.BYTE;
            }
            switch (mode) {
              case Mode.NUMERIC:
                return new NumericData(data);

              case Mode.ALPHANUMERIC:
                return new AlphanumericData(data);

              case Mode.KANJI:
                return new KanjiData(data);

              case Mode.BYTE:
                return new ByteData(data);
            }
        }
        exports.fromArray = function fromArray(array) {
            return array.reduce(function(acc, seg) {
                if (typeof seg === "string") {
                    acc.push(buildSingleSegment(seg, null));
                } else if (seg.data) {
                    acc.push(buildSingleSegment(seg.data, seg.mode));
                }
                return acc;
            }, []);
        };
        exports.fromString = function fromString(data, version) {
            const segs = getSegmentsFromString(data, Utils.isKanjiModeEnabled());
            const nodes = buildNodes(segs);
            const graph = buildGraph(nodes, version);
            const path = dijkstra.find_path(graph.map, "start", "end");
            const optimizedSegs = [];
            for (let i = 1; i < path.length - 1; i++) {
                optimizedSegs.push(graph.table[path[i]].node);
            }
            return exports.fromArray(mergeSegments(optimizedSegs));
        };
        exports.rawSplit = function rawSplit(data) {
            return exports.fromArray(getSegmentsFromString(data, Utils.isKanjiModeEnabled()));
        };
    },
    "./node_modules/qrcode/lib/core/utils.js"(__unused_webpack_module, exports) {
        let toSJISFunction;
        const CODEWORDS_COUNT = [ 0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346, 404, 466, 532, 581, 655, 733, 815, 901, 991, 1085, 1156, 1258, 1364, 1474, 1588, 1706, 1828, 1921, 2051, 2185, 2323, 2465, 2611, 2761, 2876, 3034, 3196, 3362, 3532, 3706 ];
        exports.getSymbolSize = function getSymbolSize(version) {
            if (!version) throw new Error('"version" cannot be null or undefined');
            if (version < 1 || version > 40) throw new Error('"version" should be in range from 1 to 40');
            return version * 4 + 17;
        };
        exports.getSymbolTotalCodewords = function getSymbolTotalCodewords(version) {
            return CODEWORDS_COUNT[version];
        };
        exports.getBCHDigit = function(data) {
            let digit = 0;
            while (data !== 0) {
                digit++;
                data >>>= 1;
            }
            return digit;
        };
        exports.setToSJISFunction = function setToSJISFunction(f) {
            if (typeof f !== "function") {
                throw new Error('"toSJISFunc" is not a valid function.');
            }
            toSJISFunction = f;
        };
        exports.isKanjiModeEnabled = function() {
            return typeof toSJISFunction !== "undefined";
        };
        exports.toSJIS = function toSJIS(kanji) {
            return toSJISFunction(kanji);
        };
    },
    "./node_modules/qrcode/lib/core/version-check.js"(__unused_webpack_module, exports) {
        exports.isValid = function isValid(version) {
            return !isNaN(version) && version >= 1 && version <= 40;
        };
    },
    "./node_modules/qrcode/lib/core/version.js"(__unused_webpack_module, exports, __webpack_require__) {
        const Utils = __webpack_require__("./node_modules/qrcode/lib/core/utils.js");
        const ECCode = __webpack_require__("./node_modules/qrcode/lib/core/error-correction-code.js");
        const ECLevel = __webpack_require__("./node_modules/qrcode/lib/core/error-correction-level.js");
        const Mode = __webpack_require__("./node_modules/qrcode/lib/core/mode.js");
        const VersionCheck = __webpack_require__("./node_modules/qrcode/lib/core/version-check.js");
        const G18 = 1 << 12 | 1 << 11 | 1 << 10 | 1 << 9 | 1 << 8 | 1 << 5 | 1 << 2 | 1 << 0;
        const G18_BCH = Utils.getBCHDigit(G18);
        function getBestVersionForDataLength(mode, length, errorCorrectionLevel) {
            for (let currentVersion = 1; currentVersion <= 40; currentVersion++) {
                if (length <= exports.getCapacity(currentVersion, errorCorrectionLevel, mode)) {
                    return currentVersion;
                }
            }
            return undefined;
        }
        function getReservedBitsCount(mode, version) {
            return Mode.getCharCountIndicator(mode, version) + 4;
        }
        function getTotalBitsFromDataArray(segments, version) {
            let totalBits = 0;
            segments.forEach(function(data) {
                const reservedBits = getReservedBitsCount(data.mode, version);
                totalBits += reservedBits + data.getBitsLength();
            });
            return totalBits;
        }
        function getBestVersionForMixedData(segments, errorCorrectionLevel) {
            for (let currentVersion = 1; currentVersion <= 40; currentVersion++) {
                const length = getTotalBitsFromDataArray(segments, currentVersion);
                if (length <= exports.getCapacity(currentVersion, errorCorrectionLevel, Mode.MIXED)) {
                    return currentVersion;
                }
            }
            return undefined;
        }
        exports.from = function from(value, defaultValue) {
            if (VersionCheck.isValid(value)) {
                return parseInt(value, 10);
            }
            return defaultValue;
        };
        exports.getCapacity = function getCapacity(version, errorCorrectionLevel, mode) {
            if (!VersionCheck.isValid(version)) {
                throw new Error("Invalid QR Code version");
            }
            if (typeof mode === "undefined") mode = Mode.BYTE;
            const totalCodewords = Utils.getSymbolTotalCodewords(version);
            const ecTotalCodewords = ECCode.getTotalCodewordsCount(version, errorCorrectionLevel);
            const dataTotalCodewordsBits = (totalCodewords - ecTotalCodewords) * 8;
            if (mode === Mode.MIXED) return dataTotalCodewordsBits;
            const usableBits = dataTotalCodewordsBits - getReservedBitsCount(mode, version);
            switch (mode) {
              case Mode.NUMERIC:
                return Math.floor(usableBits / 10 * 3);

              case Mode.ALPHANUMERIC:
                return Math.floor(usableBits / 11 * 2);

              case Mode.KANJI:
                return Math.floor(usableBits / 13);

              case Mode.BYTE:
              default:
                return Math.floor(usableBits / 8);
            }
        };
        exports.getBestVersionForData = function getBestVersionForData(data, errorCorrectionLevel) {
            let seg;
            const ecl = ECLevel.from(errorCorrectionLevel, ECLevel.M);
            if (Array.isArray(data)) {
                if (data.length > 1) {
                    return getBestVersionForMixedData(data, ecl);
                }
                if (data.length === 0) {
                    return 1;
                }
                seg = data[0];
            } else {
                seg = data;
            }
            return getBestVersionForDataLength(seg.mode, seg.getLength(), ecl);
        };
        exports.getEncodedBits = function getEncodedBits(version) {
            if (!VersionCheck.isValid(version) || version < 7) {
                throw new Error("Invalid QR Code version");
            }
            let d = version << 12;
            while (Utils.getBCHDigit(d) - G18_BCH >= 0) {
                d ^= G18 << Utils.getBCHDigit(d) - G18_BCH;
            }
            return version << 12 | d;
        };
    },
    "./node_modules/qrcode/lib/renderer/canvas.js"(__unused_webpack_module, exports, __webpack_require__) {
        const Utils = __webpack_require__("./node_modules/qrcode/lib/renderer/utils.js");
        function clearCanvas(ctx, canvas, size) {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            if (!canvas.style) canvas.style = {};
            canvas.height = size;
            canvas.width = size;
            canvas.style.height = size + "px";
            canvas.style.width = size + "px";
        }
        function getCanvasElement() {
            try {
                return document.createElement("canvas");
            } catch (e) {
                throw new Error("You need to specify a canvas element");
            }
        }
        exports.render = function render(qrData, canvas, options) {
            let opts = options;
            let canvasEl = canvas;
            if (typeof opts === "undefined" && (!canvas || !canvas.getContext)) {
                opts = canvas;
                canvas = undefined;
            }
            if (!canvas) {
                canvasEl = getCanvasElement();
            }
            opts = Utils.getOptions(opts);
            const size = Utils.getImageWidth(qrData.modules.size, opts);
            const ctx = canvasEl.getContext("2d");
            const image = ctx.createImageData(size, size);
            Utils.qrToImageData(image.data, qrData, opts);
            clearCanvas(ctx, canvasEl, size);
            ctx.putImageData(image, 0, 0);
            return canvasEl;
        };
        exports.renderToDataURL = function renderToDataURL(qrData, canvas, options) {
            let opts = options;
            if (typeof opts === "undefined" && (!canvas || !canvas.getContext)) {
                opts = canvas;
                canvas = undefined;
            }
            if (!opts) opts = {};
            const canvasEl = exports.render(qrData, canvas, opts);
            const type = opts.type || "image/png";
            const rendererOpts = opts.rendererOpts || {};
            return canvasEl.toDataURL(type, rendererOpts.quality);
        };
    },
    "./node_modules/qrcode/lib/renderer/svg-tag.js"(__unused_webpack_module, exports, __webpack_require__) {
        const Utils = __webpack_require__("./node_modules/qrcode/lib/renderer/utils.js");
        function getColorAttrib(color, attrib) {
            const alpha = color.a / 255;
            const str = attrib + '="' + color.hex + '"';
            return alpha < 1 ? str + " " + attrib + '-opacity="' + alpha.toFixed(2).slice(1) + '"' : str;
        }
        function svgCmd(cmd, x, y) {
            let str = cmd + x;
            if (typeof y !== "undefined") str += " " + y;
            return str;
        }
        function qrToPath(data, size, margin) {
            let path = "";
            let moveBy = 0;
            let newRow = false;
            let lineLength = 0;
            for (let i = 0; i < data.length; i++) {
                const col = Math.floor(i % size);
                const row = Math.floor(i / size);
                if (!col && !newRow) newRow = true;
                if (data[i]) {
                    lineLength++;
                    if (!(i > 0 && col > 0 && data[i - 1])) {
                        path += newRow ? svgCmd("M", col + margin, .5 + row + margin) : svgCmd("m", moveBy, 0);
                        moveBy = 0;
                        newRow = false;
                    }
                    if (!(col + 1 < size && data[i + 1])) {
                        path += svgCmd("h", lineLength);
                        lineLength = 0;
                    }
                } else {
                    moveBy++;
                }
            }
            return path;
        }
        exports.render = function render(qrData, options, cb) {
            const opts = Utils.getOptions(options);
            const size = qrData.modules.size;
            const data = qrData.modules.data;
            const qrcodesize = size + opts.margin * 2;
            const bg = !opts.color.light.a ? "" : "<path " + getColorAttrib(opts.color.light, "fill") + ' d="M0 0h' + qrcodesize + "v" + qrcodesize + 'H0z"/>';
            const path = "<path " + getColorAttrib(opts.color.dark, "stroke") + ' d="' + qrToPath(data, size, opts.margin) + '"/>';
            const viewBox = 'viewBox="' + "0 0 " + qrcodesize + " " + qrcodesize + '"';
            const width = !opts.width ? "" : 'width="' + opts.width + '" height="' + opts.width + '" ';
            const svgTag = '<svg xmlns="http://www.w3.org/2000/svg" ' + width + viewBox + ' shape-rendering="crispEdges">' + bg + path + "</svg>\n";
            if (typeof cb === "function") {
                cb(null, svgTag);
            }
            return svgTag;
        };
    },
    "./node_modules/qrcode/lib/renderer/utils.js"(__unused_webpack_module, exports) {
        function hex2rgba(hex) {
            if (typeof hex === "number") {
                hex = hex.toString();
            }
            if (typeof hex !== "string") {
                throw new Error("Color should be defined as hex string");
            }
            let hexCode = hex.slice().replace("#", "").split("");
            if (hexCode.length < 3 || hexCode.length === 5 || hexCode.length > 8) {
                throw new Error("Invalid hex color: " + hex);
            }
            if (hexCode.length === 3 || hexCode.length === 4) {
                hexCode = Array.prototype.concat.apply([], hexCode.map(function(c) {
                    return [ c, c ];
                }));
            }
            if (hexCode.length === 6) hexCode.push("F", "F");
            const hexValue = parseInt(hexCode.join(""), 16);
            return {
                r: hexValue >> 24 & 255,
                g: hexValue >> 16 & 255,
                b: hexValue >> 8 & 255,
                a: hexValue & 255,
                hex: "#" + hexCode.slice(0, 6).join("")
            };
        }
        exports.getOptions = function getOptions(options) {
            if (!options) options = {};
            if (!options.color) options.color = {};
            const margin = typeof options.margin === "undefined" || options.margin === null || options.margin < 0 ? 4 : options.margin;
            const width = options.width && options.width >= 21 ? options.width : undefined;
            const scale = options.scale || 4;
            return {
                width,
                scale: width ? 4 : scale,
                margin,
                color: {
                    dark: hex2rgba(options.color.dark || "#000000ff"),
                    light: hex2rgba(options.color.light || "#ffffffff")
                },
                type: options.type,
                rendererOpts: options.rendererOpts || {}
            };
        };
        exports.getScale = function getScale(qrSize, opts) {
            return opts.width && opts.width >= qrSize + opts.margin * 2 ? opts.width / (qrSize + opts.margin * 2) : opts.scale;
        };
        exports.getImageWidth = function getImageWidth(qrSize, opts) {
            const scale = exports.getScale(qrSize, opts);
            return Math.floor((qrSize + opts.margin * 2) * scale);
        };
        exports.qrToImageData = function qrToImageData(imgData, qr, opts) {
            const size = qr.modules.size;
            const data = qr.modules.data;
            const scale = exports.getScale(size, opts);
            const symbolSize = Math.floor((size + opts.margin * 2) * scale);
            const scaledMargin = opts.margin * scale;
            const palette = [ opts.color.light, opts.color.dark ];
            for (let i = 0; i < symbolSize; i++) {
                for (let j = 0; j < symbolSize; j++) {
                    let posDst = (i * symbolSize + j) * 4;
                    let pxColor = opts.color.light;
                    if (i >= scaledMargin && j >= scaledMargin && i < symbolSize - scaledMargin && j < symbolSize - scaledMargin) {
                        const iSrc = Math.floor((i - scaledMargin) / scale);
                        const jSrc = Math.floor((j - scaledMargin) / scale);
                        pxColor = palette[data[iSrc * size + jSrc] ? 1 : 0];
                    }
                    imgData[posDst++] = pxColor.r;
                    imgData[posDst++] = pxColor.g;
                    imgData[posDst++] = pxColor.b;
                    imgData[posDst] = pxColor.a;
                }
            }
        };
    },
    "./node_modules/@babel/runtime/helpers/esm/arrayLikeToArray.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _arrayLikeToArray
        });
        function _arrayLikeToArray(r, a) {
            (null == a || a > r.length) && (a = r.length);
            for (var e = 0, n = Array(a); e < a; e++) n[e] = r[e];
            return n;
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/arrayWithHoles.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _arrayWithHoles
        });
        function _arrayWithHoles(r) {
            if (Array.isArray(r)) return r;
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/defineProperty.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _defineProperty
        });
        var _toPropertyKey_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/toPropertyKey.js");
        function _defineProperty(e, r, t) {
            return (r = (0, _toPropertyKey_js__WEBPACK_IMPORTED_MODULE_0__["default"])(r)) in e ? Object.defineProperty(e, r, {
                value: t,
                enumerable: !0,
                configurable: !0,
                writable: !0
            }) : e[r] = t, e;
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/iterableToArrayLimit.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _iterableToArrayLimit
        });
        function _iterableToArrayLimit(r, l) {
            var t = null == r ? null : "undefined" != typeof Symbol && r[Symbol.iterator] || r["@@iterator"];
            if (null != t) {
                var e, n, i, u, a = [], f = !0, o = !1;
                try {
                    if (i = (t = t.call(r)).next, 0 === l) {
                        if (Object(t) !== t) return;
                        f = !1;
                    } else for (;!(f = (e = i.call(t)).done) && (a.push(e.value), a.length !== l); f = !0) ;
                } catch (r) {
                    o = !0, n = r;
                } finally {
                    try {
                        if (!f && null != t["return"] && (u = t["return"](), Object(u) !== u)) return;
                    } finally {
                        if (o) throw n;
                    }
                }
                return a;
            }
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/nonIterableRest.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _nonIterableRest
        });
        function _nonIterableRest() {
            throw new TypeError("Invalid attempt to destructure non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.");
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/slicedToArray.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _slicedToArray
        });
        var _arrayWithHoles_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/arrayWithHoles.js");
        var _iterableToArrayLimit_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/iterableToArrayLimit.js");
        var _unsupportedIterableToArray_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/unsupportedIterableToArray.js");
        var _nonIterableRest_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/nonIterableRest.js");
        function _slicedToArray(r, e) {
            return (0, _arrayWithHoles_js__WEBPACK_IMPORTED_MODULE_0__["default"])(r) || (0, 
            _iterableToArrayLimit_js__WEBPACK_IMPORTED_MODULE_1__["default"])(r, e) || (0, _unsupportedIterableToArray_js__WEBPACK_IMPORTED_MODULE_2__["default"])(r, e) || (0, 
            _nonIterableRest_js__WEBPACK_IMPORTED_MODULE_3__["default"])();
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/toPrimitive.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => toPrimitive
        });
        var _typeof_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/typeof.js");
        function toPrimitive(t, r) {
            if ("object" != (0, _typeof_js__WEBPACK_IMPORTED_MODULE_0__["default"])(t) || !t) return t;
            var e = t[Symbol.toPrimitive];
            if (void 0 !== e) {
                var i = e.call(t, r || "default");
                if ("object" != (0, _typeof_js__WEBPACK_IMPORTED_MODULE_0__["default"])(i)) return i;
                throw new TypeError("@@toPrimitive must return a primitive value.");
            }
            return ("string" === r ? String : Number)(t);
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/toPropertyKey.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => toPropertyKey
        });
        var _typeof_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/typeof.js");
        var _toPrimitive_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/toPrimitive.js");
        function toPropertyKey(t) {
            var i = (0, _toPrimitive_js__WEBPACK_IMPORTED_MODULE_1__["default"])(t, "string");
            return "symbol" == (0, _typeof_js__WEBPACK_IMPORTED_MODULE_0__["default"])(i) ? i : i + "";
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/typeof.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _typeof
        });
        function _typeof(o) {
            "@babel/helpers - typeof";
            return _typeof = "function" == typeof Symbol && "symbol" == typeof Symbol.iterator ? function(o) {
                return typeof o;
            } : function(o) {
                return o && "function" == typeof Symbol && o.constructor === Symbol && o !== Symbol.prototype ? "symbol" : typeof o;
            }, _typeof(o);
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/unsupportedIterableToArray.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _unsupportedIterableToArray
        });
        var _arrayLikeToArray_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/arrayLikeToArray.js");
        function _unsupportedIterableToArray(r, a) {
            if (r) {
                if ("string" == typeof r) return (0, _arrayLikeToArray_js__WEBPACK_IMPORTED_MODULE_0__["default"])(r, a);
                var t = {}.toString.call(r).slice(8, -1);
                return "Object" === t && r.constructor && (t = r.constructor.name), "Map" === t || "Set" === t ? Array.from(r) : "Arguments" === t || /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(t) ? (0, 
                _arrayLikeToArray_js__WEBPACK_IMPORTED_MODULE_0__["default"])(r, a) : void 0;
            }
        }
    }
};

var __webpack_module_cache__ = {};

function __webpack_require__(moduleId) {
    var cachedModule = __webpack_module_cache__[moduleId];
    if (cachedModule !== undefined) {
        return cachedModule.exports;
    }
    var module = __webpack_module_cache__[moduleId] = {
        exports: {}
    };
    if (!(moduleId in __webpack_modules__)) {
        delete __webpack_module_cache__[moduleId];
        var e = new Error("Cannot find module '" + moduleId + "'");
        e.code = "MODULE_NOT_FOUND";
        throw e;
    }
    __webpack_modules__[moduleId](module, module.exports, __webpack_require__);
    return module.exports;
}

(() => {
    __webpack_require__.d = (exports, definition) => {
        for (var key in definition) {
            if (__webpack_require__.o(definition, key) && !__webpack_require__.o(exports, key)) {
                Object.defineProperty(exports, key, {
                    enumerable: true,
                    get: definition[key]
                });
            }
        }
    };
})();

(() => {
    __webpack_require__.o = (obj, prop) => Object.prototype.hasOwnProperty.call(obj, prop);
})();

(() => {
    __webpack_require__.r = exports => {
        if (typeof Symbol !== "undefined" && Symbol.toStringTag) {
            Object.defineProperty(exports, Symbol.toStringTag, {
                value: "Module"
            });
        }
        Object.defineProperty(exports, "__esModule", {
            value: true
        });
    };
})();

var __webpack_exports__ = {};

(() => {
    "use strict";
    __webpack_require__.r(__webpack_exports__);
    __webpack_require__.d(__webpack_exports__, {
        default: () => __WEBPACK_DEFAULT_EXPORT__
    });
    var _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/slicedToArray.js");
    var _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/typeof.js");
    var _EzauthCore__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__("./src/ezauth/js/EzauthCore.js");
    var _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__("./src/ezauth/js/EzauthUtils.js");
    var _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__("./src/ezauth/js/EzauthErrorHandler.js");
    var _EzauthModal__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__("./src/ezauth/js/EzauthModal.js");
    var _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__("./src/ezauth/js/EzauthAlert.js");
    var _EzauthBlock__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__("./src/ezauth/js/EzauthBlock.js");
    var qrcode__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__("./node_modules/qrcode/lib/browser.js");
    var _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__ = __webpack_require__("./src/ezauth/utils/Logger.js");
    function _createForOfIteratorHelper(r, e) {
        var t = "undefined" != typeof Symbol && r[Symbol.iterator] || r["@@iterator"];
        if (!t) {
            if (Array.isArray(r) || (t = _unsupportedIterableToArray(r)) || e && r && "number" == typeof r.length) {
                t && (r = t);
                var _n = 0, F = function F() {};
                return {
                    s: F,
                    n: function n() {
                        return _n >= r.length ? {
                            done: !0
                        } : {
                            done: !1,
                            value: r[_n++]
                        };
                    },
                    e: function e(r) {
                        throw r;
                    },
                    f: F
                };
            }
            throw new TypeError("Invalid attempt to iterate non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.");
        }
        var o, a = !0, u = !1;
        return {
            s: function s() {
                t = t.call(r);
            },
            n: function n() {
                var r = t.next();
                return a = r.done, r;
            },
            e: function e(r) {
                u = !0, o = r;
            },
            f: function f() {
                try {
                    a || null == t["return"] || t["return"]();
                } finally {
                    if (u) throw o;
                }
            }
        };
    }
    function _unsupportedIterableToArray(r, a) {
        if (r) {
            if ("string" == typeof r) return _arrayLikeToArray(r, a);
            var t = {}.toString.call(r).slice(8, -1);
            return "Object" === t && r.constructor && (t = r.constructor.name), "Map" === t || "Set" === t ? Array.from(r) : "Arguments" === t || /^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(t) ? _arrayLikeToArray(r, a) : void 0;
        }
    }
    function _arrayLikeToArray(r, a) {
        (null == a || a > r.length) && (a = r.length);
        for (var e = 0, n = Array(a); e < a; e++) n[e] = r[e];
        return n;
    }
    var EzauthChild = {};
    (function(child) {
        child.getElements = function() {
            return {
                providerListOn: document.querySelector("#EzauthContainer .body .step1 .provider-list li.on"),
                buttonsScope: document.querySelector(".body .step1 .user-info .buttons"),
                cloudButton: document.querySelector(".cloud-button"),
                appPushButton: document.querySelector(".app-push-button"),
                qrButton: document.querySelector(".qr-button"),
                closeButton: document.querySelector("#EzauthContainer > div > header > div.right > div > img"),
                reqAuthButton: document.querySelector("#EzauthContainer > div > div > section.step1 > article.right > section.buttons > div.button.req-auth"),
                step01CompleteAuthButton: document.querySelector("#EzauthContainer > div > div > section.step1 > article > section.buttons > div.button.complete-auth"),
                step2CompleteAuthButton: document.querySelector("#EzauthContainer > div > div > section.step2 > article > section.buttons > div.button.complete-auth"),
                providerList: document.querySelector(".provider-list li:first-child button")
            };
        };
        child.cleanupPolling = function() {
            if (child.currentAbortController) {
                child.currentAbortController.abort();
            }
        };
        child.cleanupTimer = function() {
            if (child.currentTimerId) {
                clearInterval(child.currentTimerId);
                child.currentTimerId = null;
            }
        };
        child.cleanupPollingAndTimer = function() {
            child.cleanupPolling();
            child.cleanupTimer();
        };
        child.resetAuthMethodButtons = function(scope, activeButton) {
            if (!scope) return;
            scope.querySelectorAll(".app-push-button, .qr-button, .cloud-button").forEach(function(btn) {
                btn.classList.remove("on");
                var label = btn.textContent.trim();
                btn.setAttribute("title", "".concat(label, " 선택되지않음"));
                btn.setAttribute("aria-pressed", "false");
            });
            if (activeButton) {
                activeButton.classList.add("on");
                var label = activeButton.textContent.trim();
                activeButton.setAttribute("title", "".concat(label, " 선택됨"));
                activeButton.setAttribute("aria-pressed", "true");
            }
        };
        child.registEvent = function() {
            if (window.addEventListener) {
                window.addEventListener("message", child.eventHandler, false);
            } else if (window.attachEvent) {
                window.attachEvent("onmessage", child.eventHandler);
            }
            var reqAuthButton = document.querySelector("#EzauthContainer > div > div > section.step1 > article.right > section.buttons > div.button.req-auth");
            if (reqAuthButton) {
                reqAuthButton.addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (!event.shiftKey) {
                            event.preventDefault();
                            var _targetElement = document.querySelector(".provider-list li:first-child button");
                            _targetElement.focus();
                        }
                    }
                });
            }
            var step01CompleteAuthButton = document.querySelector("#EzauthContainer > div > div > section.step1 > article > section.buttons > div.button.complete-auth");
            if (step01CompleteAuthButton) {
                step01CompleteAuthButton.addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (!event.shiftKey) {
                            event.preventDefault();
                            var _targetElement = document.querySelector(".provider-list li:first-child button");
                            _targetElement.focus();
                        }
                    }
                });
            }
            var completeAuthButton = document.querySelector("#EzauthContainer > div > div > section.step2 > article > section.buttons > div.button.complete-auth");
            if (completeAuthButton) {
                completeAuthButton.addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (!event.shiftKey) {
                            event.preventDefault();
                            var _linkElements = document.querySelector("#EzauthContainer > div > div > section.step2 a:first-of-type");
                            var _targetElement = document.querySelector("#EzauthContainer > div > div > section.step2 > article > section.buttons > div.button.close");
                            if (_linkElements) {
                                _linkElements.focus();
                            } else {
                                _targetElement.focus();
                            }
                        }
                    }
                });
            }
            var closeButtonImgHeader = document.querySelector("#EzauthContainer > div > header > div.right > div > img");
            if (closeButtonImgHeader) {
                closeButtonImgHeader.addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (event.shiftKey) {
                            var step2CompleteAuthButton = document.querySelector("#EzauthContainer > div > div > section.step2 > article > section.buttons > div.button.complete-auth");
                            var step1ReqAuthButton = document.querySelector("#EzauthContainer > div > div > section.step1 > article.right > section.buttons > div.button.req-auth");
                            var isStep2CompleteAuthButtonHidden = !step2CompleteAuthButton || step2CompleteAuthButton.style.display === "none";
                            var isStep1ReqAuthButtonHidden = !step1ReqAuthButton || step1ReqAuthButton.style.display === "none";
                            if (isStep2CompleteAuthButtonHidden) {
                                event.preventDefault();
                                if (step1ReqAuthButton) {
                                    step1ReqAuthButton.focus();
                                }
                            } else if (isStep1ReqAuthButtonHidden) {
                                event.preventDefault();
                                if (step2CompleteAuthButton) {
                                    step2CompleteAuthButton.focus();
                                }
                            }
                        }
                    }
                });
            }
            var ezauthContainer = document.getElementById("EzauthContainer");
            if (ezauthContainer) {
                ezauthContainer.addEventListener("click", handleDelegatedClickAndKeyup);
                ezauthContainer.addEventListener("keyup", handleDelegatedClickAndKeyup);
            }
            function handleDelegatedClickAndKeyup(event) {
                if (event.type === "keyup" && event.which !== 13 && event.which !== 32) {
                    return;
                }
                var targetElement = event.target;
                if (targetElement.closest(".body .step1 .user-info .buttons")) {
                    var button = targetElement.closest(".sign_type_buttons .button-show");
                    if (!button) return;
                    document.querySelectorAll(".sign_type_buttons .button-show").forEach(function(btn) {
                        var label = btn.textContent.trim();
                        btn.setAttribute("title", "".concat(label, " 선택되지않음"));
                        btn.setAttribute("aria-pressed", "false");
                    });
                    var label = button.textContent.trim();
                    button.setAttribute("title", "".concat(label, " 선택됨"));
                    button.setAttribute("aria-pressed", "true");
                    if (!targetElement.classList.contains("on")) {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].getTxid(function(data) {
                            if (data.errno !== 0) {
                                if (typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined") {
                                    _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                                }
                            }
                        });
                    }
                }
                if (targetElement.closest("header .close img")) {
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined") {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_SIMPLEAUTH_CANCEL, null);
                    }
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].info("[UI 닫힘(X 버튼)으로 폴링 중단]");
                    child.cleanupPolling();
                } else if (targetElement.tagName === "INPUT") {
                    var liElement = document.querySelector("#EzauthContainer .body .step1 .provider-list li.on");
                    if (liElement == null) {
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.certNotSelected);
                    }
                } else if (targetElement.closest(".body .step1 .buttons .button.close")) {
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined") {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_SIMPLEAUTH_CANCEL, null);
                    }
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].info("[UI 닫힘(닫기 버튼)으로 폴링 중단]");
                    child.cleanupPolling();
                } else if (targetElement.closest(".body .step1 .provider-list li")) {
                    var _liElement = targetElement.closest(".body .step1 .provider-list li");
                    if (_liElement.classList.contains("on")) return;
                    var listItems = document.querySelectorAll("#EzauthContainer .body .step1 .user-info li");
                    var _iterator = _createForOfIteratorHelper(listItems), _step;
                    try {
                        for (_iterator.s(); !(_step = _iterator.n()).done; ) {
                            var li = _step.value;
                            if (window.getComputedStyle(li).display !== "none") {
                                var input = li.querySelector("input:not([type='hidden'])");
                                if (input) {
                                    input.focus();
                                    break;
                                }
                            }
                        }
                    } catch (err) {
                        _iterator.e(err);
                    } finally {
                        _iterator.f();
                    }
                    var updateAuthProviderUI = function updateAuthProviderUI() {
                        if (_liElement.dataset.provider) {
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined") {
                                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = JSON.parse(_liElement.dataset.provider);
                            }
                        }
                        if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) {
                            var dimmed = document.querySelector(".body .step1 .right .area_dimmed");
                            if (dimmed) dimmed.classList.remove("active");
                        }
                        document.querySelectorAll("#EzauthContainer .body .step1 .provider-list li").forEach(function(el) {
                            el.classList.remove("on");
                            var btn = el.querySelector("button");
                            if (btn) {
                                btn.setAttribute("aria-pressed", "false");
                                btn.setAttribute("aria-label", "".concat(el.querySelector("p").textContent, " 선택되지 않음"));
                            }
                        });
                        var allAgreeCheckbox = document.querySelector("#EzauthContainer .body .step1 .policys .policy-title img.checkbox");
                        if (allAgreeCheckbox && allAgreeCheckbox.classList.contains("on")) {
                            allAgreeCheckbox.classList.remove("on");
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                allAgreeCheckbox.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_all_nor.svg");
                            }
                            allAgreeCheckbox.setAttribute("aria-checked", "false");
                        }
                        document.querySelectorAll("#EzauthContainer .body .step1 .policys .policy img.checkbox").forEach(function(el) {
                            el.classList.remove("on");
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                el.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_nor.svg");
                            }
                            el.setAttribute("aria-checked", "false");
                        });
                        _liElement.classList.add("on");
                        var selectedBtn = _liElement.querySelector("button");
                        if (selectedBtn) {
                            selectedBtn.setAttribute("aria-pressed", "true");
                            selectedBtn.setAttribute("aria-label", "".concat(_liElement.querySelector("p").textContent, " 선택됨"));
                        }
                        var scope = document.querySelector(".body .step1 .user-info .buttons");
                        scope.querySelectorAll(".app-push-button, .qr-button, .cloud-button").forEach(function(btn) {
                            var label = btn.textContent.trim();
                            btn.setAttribute("title", "".concat(label, " 선택되지않음"));
                            btn.setAttribute("aria-pressed", "false");
                            btn.classList.remove("on");
                        });
                        var pushInfo = document.querySelector(".body .step1 .user-info .push-info");
                        var policys = document.querySelector(".body .step1 .policys .encase");
                        var cloudBtn = document.getElementById("cloud-btn");
                        var appPushBtn = document.getElementById("app-push-btn");
                        if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerUseCloud === "Y") {
                            if (cloudBtn) cloudBtn.classList.add("on");
                            if (appPushBtn) appPushBtn.classList.remove("on");
                            if (pushInfo) pushInfo.style.display = "none";
                            if (policys) policys.style.display = "none";
                            var _label = cloudBtn.textContent.trim();
                            cloudBtn.setAttribute("title", "".concat(_label, " 선택됨"));
                            cloudBtn.setAttribute("aria-pressed", "true");
                        } else {
                            if (cloudBtn) cloudBtn.classList.remove("on");
                            if (appPushBtn) appPushBtn.classList.add("on");
                            if (pushInfo) pushInfo.style.display = "block";
                            if (policys) policys.style.display = "block";
                            var _label2 = appPushBtn.textContent.trim();
                            appPushBtn.setAttribute("title", "".concat(_label2, " 선택됨"));
                            appPushBtn.setAttribute("aria-pressed", "true");
                        }
                        child.makeEzauthUiUserInfo(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider);
                    };
                    child.cleanupPollingAndTimer();
                    updateAuthProviderUI();
                } else if (targetElement.closest(".body .step1 .policys .policy-title .all-agree")) {
                    var checkboxImg = document.querySelector("#EzauthContainer .body .step1 .policys .policy-title img.checkbox");
                    if (checkboxImg) {
                        checkboxImg.click();
                    }
                } else if (targetElement.closest(".body .step1 .policys .policy-title img.checkbox")) {
                    var _checkboxImg = targetElement.closest(".body .step1 .policys .policy-title img.checkbox");
                    if (!_checkboxImg) return;
                    if (_checkboxImg.classList.contains("on")) {
                        _checkboxImg.classList.remove("on");
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                            _checkboxImg.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_all_nor.svg");
                        }
                        _checkboxImg.setAttribute("aria-checked", "false");
                        document.querySelectorAll("#EzauthContainer .body .step1 .policys .policy img.checkbox").forEach(function(el) {
                            el.classList.remove("on");
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                el.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_nor.svg");
                            }
                            el.setAttribute("aria-checked", "false");
                        });
                    } else {
                        _checkboxImg.classList.add("on");
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                            _checkboxImg.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_all_nor_1.svg");
                        }
                        _checkboxImg.setAttribute("aria-checked", "true");
                        document.querySelectorAll("#EzauthContainer .body .step1 .policys .policy img.checkbox").forEach(function(el) {
                            el.classList.add("on");
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                el.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_nor_1.svg");
                            }
                            el.setAttribute("aria-checked", "true");
                        });
                    }
                } else if (targetElement.closest(".body .step1 .policys .policy .text")) {
                    var policyElement = targetElement.closest(".policy");
                    var _checkboxImg2 = policyElement ? policyElement.querySelector("img.checkbox") : null;
                    if (_checkboxImg2) {
                        _checkboxImg2.click();
                    }
                } else if (targetElement.closest(".body .step1 .policys .policy img.checkbox")) {
                    var _checkboxImg3 = targetElement.closest(".body .step1 .policys .policy img.checkbox");
                    if (!_checkboxImg3) return;
                    var parentPolicys = _checkboxImg3.closest(".policys");
                    if (!parentPolicys) return;
                    if (_checkboxImg3.classList.contains("on")) {
                        _checkboxImg3.classList.remove("on");
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                            _checkboxImg3.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_nor.svg");
                        }
                        _checkboxImg3.setAttribute("aria-checked", "false");
                        var allAgreeCheckbox = parentPolicys.querySelector(".policy-title img.checkbox");
                        if (allAgreeCheckbox) {
                            allAgreeCheckbox.classList.remove("on");
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                allAgreeCheckbox.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_all_nor.svg");
                                allAgreeCheckbox.setAttribute("aria-checked", "false");
                            }
                        }
                    } else {
                        _checkboxImg3.classList.add("on");
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                            _checkboxImg3.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_nor_1.svg");
                        }
                        _checkboxImg3.setAttribute("aria-checked", "true");
                        if (child.isAllPolicyChecked()) {
                            var _allAgreeCheckbox = parentPolicys.querySelector(".policy-title img.checkbox");
                            if (_allAgreeCheckbox) {
                                _allAgreeCheckbox.classList.add("on");
                                if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                    _allAgreeCheckbox.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_all_nor_1.svg");
                                }
                                _allAgreeCheckbox.setAttribute("aria-checked", "true");
                            }
                        }
                    }
                } else if (targetElement.closest(".body .step1 .policys li .button-show")) {
                    var _EzauthCore$ezauthJso;
                    var policyLi = targetElement.closest(".body .step1 .policys li");
                    var id = policyLi ? policyLi.getAttribute("id") : null;
                    if (id && typeof _EzauthModal__WEBPACK_IMPORTED_MODULE_5__["default"] !== "undefined" && typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && (_EzauthCore$ezauthJso = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf) !== null && _EzauthCore$ezauthJso !== void 0 && (_EzauthCore$ezauthJso = _EzauthCore$ezauthJso.policys) !== null && _EzauthCore$ezauthJso !== void 0 && _EzauthCore$ezauthJso[id]) {
                        var _EzauthCore$basicInfo;
                        _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].info("Policy button clicked:", event);
                        _EzauthModal__WEBPACK_IMPORTED_MODULE_5__["default"].show(id, (((_EzauthCore$basicInfo = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) === null || _EzauthCore$basicInfo === void 0 ? void 0 : _EzauthCore$basicInfo.ezauthRootPath) || "") + _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf.policys[id].path, null, {
                            use: true,
                            title: "확 인"
                        });
                    }
                } else if (targetElement.closest(".body .step1 .buttons .button.req-auth")) {
                    var ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined") {
                        var activeButton = document.querySelector("#EzauthContainer .body .step1 .user-info .buttons .button-show.on");
                        if (activeButton) {
                            if (activeButton.classList.contains("qr-button")) {
                                var qrRetryBtn = document.querySelector(".body .step1 .user-info .qr-info .qr-code-section .qrRetryButton");
                                if (qrRetryBtn) qrRetryBtn.click();
                            } else {
                                if (activeButton.classList.contains("cloud-button")) {
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType = "CLOUD";
                                } else if (activeButton.classList.contains("app-push-button")) {
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType = "PUSH";
                                }
                                var _liElement2 = document.querySelector(".provider-list li.on");
                                if (!_liElement2) {
                                    _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.certNotSelected);
                                    return;
                                }
                                if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].isValidUserInfo() === false) {
                                    return;
                                }
                                if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType !== "CLOUD" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].isValidPolicys() === false) {
                                    if (typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined" && typeof window.parent.EzauthConfig !== "undefined") {
                                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.notAgreeTerms);
                                    }
                                    return;
                                }
                                _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].setLocalStorageDataAsJSON("lastAuthSelection", "providerId", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerId);
                                _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].setLocalStorageDataAsJSON("lastAuthSelection", "authType", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType);
                                child.cleanupPolling();
                                child.currentAbortController = new AbortController;
                                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthRequest(function(data) {
                                    var _child$currentAbortCo, _EzauthErrorHandler$e;
                                    if (!child.currentAbortController || (_child$currentAbortCo = child.currentAbortController) !== null && _child$currentAbortCo !== void 0 && _child$currentAbortCo.signal.aborted) {
                                        return;
                                    }
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = JSON.parse(_liElement2.dataset.provider);
                                    var requestType = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType;
                                    if (data.errno === ((_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === null || _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === void 0 || (_EzauthErrorHandler$e = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error) === null || _EzauthErrorHandler$e === void 0 || (_EzauthErrorHandler$e = _EzauthErrorHandler$e.API_OK) === null || _EzauthErrorHandler$e === void 0 ? void 0 : _EzauthErrorHandler$e.errno) || 0)) {
                                        child.makeEzauthUiStep2(data.result);
                                        child.showEzauthUIStep2();
                                        if (requestType === "CLOUD") {
                                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerCloudURL = data.result.cloudUrl;
                                            child.callCloudAPI(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerCloudURL, function(success, error) {
                                                if (!success) {
                                                    _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(error);
                                                }
                                            });
                                        } else if (requestType === "PUSH") {
                                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].startPollingAuthStatus(data.result.txId, ezauthCore.ezauthJsonConf.pollingInterval, ezauthCore.ezauthJsonConf.pollingCnt, child.currentAbortController ? child.currentAbortController.signal : undefined);
                                        }
                                    } else {
                                        if (typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined") {
                                            _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                                        }
                                    }
                                });
                            }
                        }
                    }
                } else if (targetElement.closest(".body .step1 .user-info .buttons .cloud-button")) {
                    var _elements$providerLis;
                    var elements = child.getElements();
                    if (!elements.providerListOn) {
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.certNotSelected);
                        return;
                    }
                    child.cleanupPollingAndTimer();
                    child.resetAuthMethodButtons(elements.buttonsScope, targetElement);
                    var selectedProvider = (_elements$providerLis = elements.providerListOn) !== null && _elements$providerLis !== void 0 && (_elements$providerLis = _elements$providerLis.dataset) !== null && _elements$providerLis !== void 0 && _elements$providerLis.provider ? JSON.parse(elements.providerListOn.dataset.provider) : null;
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = selectedProvider;
                    child.makeEzauthUiUserInfo(selectedProvider);
                } else if (targetElement.closest(".body .step1 .user-info .buttons .app-push-button")) {
                    var _elements$providerLis2;
                    var _elements = child.getElements();
                    if (!_elements.providerListOn || !_elements.providerListOn.dataset.provider) {
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.certNotSelected);
                        return;
                    }
                    child.cleanupPollingAndTimer();
                    child.resetAuthMethodButtons(_elements.buttonsScope, targetElement);
                    var _selectedProvider = (_elements$providerLis2 = _elements.providerListOn) !== null && _elements$providerLis2 !== void 0 && (_elements$providerLis2 = _elements$providerLis2.dataset) !== null && _elements$providerLis2 !== void 0 && _elements$providerLis2.provider ? JSON.parse(_elements.providerListOn.dataset.provider) : null;
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = _selectedProvider;
                    child.makeEzauthUiUserInfo(_selectedProvider);
                } else if (targetElement.closest(".body .step1 .user-info .buttons .qr-button")) {
                    var _ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
                    if (targetElement.classList.contains("on")) return;
                    var _elements2 = child.getElements();
                    if (!_elements2.providerListOn || !_elements2.providerListOn.dataset.provider) {
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.certNotSelected);
                        return;
                    }
                    child.toggleQrLoading(true);
                    child.resetAuthMethodButtons(_elements2.buttonsScope, targetElement);
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = JSON.parse(_elements2.providerListOn.dataset.provider);
                    var isCloudProvider = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerUseCloud === "Y";
                    child.makeEzauthUiQRInfo(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider);
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType = "QR";
                    _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].setLocalStorageDataAsJSON("lastAuthSelection", "providerId", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerId);
                    _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].setLocalStorageDataAsJSON("lastAuthSelection", "authType", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType);
                    child.cleanupPollingAndTimer();
                    child.currentAbortController = new AbortController;
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthRequest(function(data) {
                        var _child$currentAbortCo2, _EzauthErrorHandler$e2;
                        child.toggleQrLoading(false);
                        if (!child.currentAbortController || (_child$currentAbortCo2 = child.currentAbortController) !== null && _child$currentAbortCo2 !== void 0 && _child$currentAbortCo2.signal.aborted) {
                            return;
                        }
                        if (data.errno === ((_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === null || _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === void 0 || (_EzauthErrorHandler$e2 = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error) === null || _EzauthErrorHandler$e2 === void 0 || (_EzauthErrorHandler$e2 = _EzauthErrorHandler$e2.API_OK) === null || _EzauthErrorHandler$e2 === void 0 ? void 0 : _EzauthErrorHandler$e2.errno) || 0)) {
                            if (data.result.qrImage === "URL") {
                                child.generateQrCode({
                                    qrScheme: data.result.qrScheme,
                                    qrImage: "URL"
                                });
                            } else if (data.result.qrImage === "IMAGE") {
                                child.generateQrCode({
                                    qrScheme: data.result.qrScheme,
                                    qrImage: "IMAGE"
                                });
                            }
                            child.showQRTimerUI(_ezauthCore.ezauthJsonConf.qrTimer);
                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].startPollingAuthStatus(data.result.txId, _ezauthCore.ezauthJsonConf.pollingInterval, _ezauthCore.ezauthJsonConf.pollingCnt, child.currentAbortController ? child.currentAbortController.signal : undefined);
                        } else {
                            _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].removeLocalStorage("lastAuthSelection");
                            if (isCloudProvider) {
                                var _document$querySelect;
                                (_document$querySelect = document.querySelector(".cloud-button")) === null || _document$querySelect === void 0 || _document$querySelect.click();
                            } else {
                                var _document$querySelect2;
                                (_document$querySelect2 = document.querySelector(".app-push-button")) === null || _document$querySelect2 === void 0 || _document$querySelect2.click();
                            }
                            if (typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined") {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                            }
                        }
                    });
                } else if (targetElement.closest(".body .step1 .user-info .qr-info .qr-code-section .qrRetryButton")) {
                    var _ezauthCore2 = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
                    child.toggleQrLoading(true);
                    var qrRetryButton = document.querySelector("#EzauthContainer .body .step1 .user-info .qr-info .qr-code-section .qrRetryButton");
                    var qrRetryButtonDimmed = document.querySelector("#EzauthContainer .body .step1 .user-info .qr-info .qr-code-section .qr-dimmed");
                    if (qrRetryButton) {
                        qrRetryButton.style.display = "none";
                        qrRetryButton.classList.remove("on");
                        qrRetryButtonDimmed.classList.remove("on");
                    }
                    var reqAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
                    if (reqAuthButtonStep1) reqAuthButtonStep1.style.display = "none";
                    var completeAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .complete-auth");
                    if (completeAuthButtonStep1) completeAuthButtonStep1.style.display = "";
                    child.cleanupPolling();
                    child.currentAbortController = new AbortController;
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].getTxid(function(data) {
                        if (data.errno === 0) {
                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthRequest(function(data) {
                                var _child$currentAbortCo3, _EzauthErrorHandler$e3;
                                child.toggleQrLoading(false);
                                if (!child.currentAbortController || (_child$currentAbortCo3 = child.currentAbortController) !== null && _child$currentAbortCo3 !== void 0 && _child$currentAbortCo3.signal.aborted) {
                                    return;
                                }
                                if (data.errno === ((_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === null || _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === void 0 || (_EzauthErrorHandler$e3 = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error) === null || _EzauthErrorHandler$e3 === void 0 || (_EzauthErrorHandler$e3 = _EzauthErrorHandler$e3.API_OK) === null || _EzauthErrorHandler$e3 === void 0 ? void 0 : _EzauthErrorHandler$e3.errno) || 0)) {
                                    if (data.result.qrImage === "URL") {
                                        child.generateQrCode({
                                            qrScheme: data.result.qrScheme,
                                            qrImage: "URL"
                                        });
                                    } else if (data.result.qrImage === "IMAGE") {
                                        child.generateQrCode({
                                            qrScheme: data.result.qrScheme,
                                            qrImage: "IMAGE"
                                        });
                                    }
                                    child.showQRTimerUI(_ezauthCore2.ezauthJsonConf.qrTimer);
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].startPollingAuthStatus(data.result.txId, _ezauthCore2.ezauthJsonConf.pollingInterval, _ezauthCore2.ezauthJsonConf.pollingCnt, child.currentAbortController ? child.currentAbortController.signal : undefined);
                                } else if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2907.errno) {
                                    _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr, null, null, null, "close", function() {
                                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2907, null);
                                    });
                                } else {
                                    _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].removeLocalStorage("lastAuthSelection");
                                    if (typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined") {
                                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                                    }
                                }
                            });
                        } else {
                            if (typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined") {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                            }
                        }
                    });
                } else if (targetElement.closest(".body .step2 .buttons .button.close")) {
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined") {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_SIMPLEAUTH_CANCEL, null);
                    }
                } else if (targetElement.closest(".body .step1 .buttons .button.complete-auth") || targetElement.closest(".body .step2 .buttons .button.complete-auth")) {
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined" && typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined") {
                        if (typeof _EzauthBlock__WEBPACK_IMPORTED_MODULE_7__["default"] !== "undefined") _EzauthBlock__WEBPACK_IMPORTED_MODULE_7__["default"].show();
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].userReqYn = "Y";
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthResult(function(data) {
                            if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthCheck(function(data) {
                                    if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                                        if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf.service === "authBiz") {
                                            var qs = document.querySelector.bind(document);
                                            var nameEl = qs("#EzauthContainer .body .step1 .user-info li.name input");
                                            var phonePrefixEl = qs("#EzauthContainer .body .step1 .user-info li.hp select.sel_telnum");
                                            var phoneSuffixEl = qs("#EzauthContainer .body .step1 .user-info li.hp input");
                                            var birthEl = qs("#EzauthContainer .body .step1 .user-info li.birth input");
                                            var bizEl = qs("#EzauthContainer .body .step1 .user-info li.biz-registration-number input");
                                            var ssn1El = qs("#EzauthContainer .body .step1 .user-info li.ssn .ssn1");
                                            var ssn2El = qs("#EzauthContainer .body .step1 .user-info li.ssn .ssn2");
                                            var telcoEl = qs("#EzauthContainer .body .step1 .user-info li.hp .telco");
                                            var entries = [ [ "name", nameEl ], [ "phone", {
                                                prefixEl: phonePrefixEl,
                                                suffixEl: phoneSuffixEl
                                            } ], [ "birthday", birthEl ], [ "businessNumber", bizEl ], [ "ssn1", ssn1El ], [ "ssn2", ssn2El ], [ "telcoTycd", telcoEl ] ];
                                            var reqBody = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].makeEncBody(entries);
                                            var body = {
                                                data: reqBody,
                                                code: "encrypt"
                                            };
                                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].endecryptReq(body, function(decryptedObj) {
                                                if (!decryptedObj || (0, _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_1__["default"])(decryptedObj) !== "object" || decryptedObj.resultCode !== "2000") return;
                                                var encData = decryptedObj.data;
                                                sessionStorage.setItem("EZAuth", encData);
                                                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].cleanUserInfo();
                                                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].cleanPolicys();
                                                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK, data);
                                            });
                                        } else {
                                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK, data);
                                        }
                                    } else {
                                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH9990, data);
                                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                                    }
                                });
                            } else if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2990.errno) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                            } else if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2906.errno) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr, null, null, null, "close", function() {
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2906, null);
                                });
                            } else if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2907.errno) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr, null, null, null, "close", function() {
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2907, null);
                                });
                            } else if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2900.errno) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr, null, null, null, "close", function() {
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2900, null);
                                });
                            } else {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                            }
                        });
                    }
                } else if (targetElement.closest(".body .step2 .infomation .ibk-cloud-confirm-btn")) {
                    var _liElement3 = document.querySelector(".body .step2 .infomation .ibk-cloud-confirm-btn");
                    var cloudUrl = _liElement3.dataset.cloudUrl;
                    if (cloudUrl) {
                        child.callCloudAPI(cloudUrl, function(success, error) {
                            if (!success) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(error);
                            }
                        });
                    }
                }
            }
            document.querySelectorAll("#EzauthContainer .body .step1 .user-info li input[numberOnly]").forEach(function(inputElement) {
                inputElement.addEventListener("keyup", function(event) {
                    if (isNaN(this.value)) {
                        this.value = "";
                        if (document.activeElement && document.activeElement.title === this.title) {
                            var _window$parent$Ezauth;
                            if (typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined" && typeof window.parent.EzauthConfig !== "undefined" && (_window$parent$Ezauth = window.parent.EzauthConfig.ui) !== null && _window$parent$Ezauth !== void 0 && _window$parent$Ezauth.errorText) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.enterOnlyNum, null, null, null, null, function() {
                                    return inputElement.focus();
                                });
                            }
                        }
                    }
                });
            });
            document.querySelectorAll("#EzauthContainer .body .step1 .user-info li .telco").forEach(function(selectElement) {
                selectElement.addEventListener("change", function() {
                    this.style.color = "#000";
                });
            });
        };
        child.callCloudAPI = function(providerCloudURL, callback) {
            var iframeURL = providerCloudURL;
            var origin = _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].getOrigin(iframeURL);
            if (!origin) {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("Invalid Cloud URL:", iframeURL);
                if (callback) callback(false, _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_CLOUD_INVALID_URL.errstr);
                return;
            }
            var iframe = document.createElement("iframe");
            iframe.src = iframeURL;
            iframe.id = "authIframe";
            iframe.style.width = "100%";
            iframe.style.height = "100%";
            iframe.style.border = "none";
            iframe.style.borderRadius = "8px";
            iframe.style.boxShadow = "0 0 20px rgba(0, 0, 0, 0.3)";
            iframe.onload = function() {
                try {
                    iframe.contentWindow.postMessage("", origin);
                    if (callback) callback(true, null);
                } catch (error) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("postMessage failed:", error);
                    if (callback) callback(false, error.message);
                }
            };
            iframe.onerror = function() {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("iframe load failed");
                if (callback) callback(false, "iframe load failed");
            };
            var container = document.getElementById("iframeContainer");
            container.innerHTML = "";
            container.style.display = "flex";
            container.appendChild(iframe);
        };
        child.makeEzauthUiWebAccessibility = function() {
            var _ezauthJsonConf$polic, _ezauthJsonConf$polic2, _ezauthJsonConf$polic3, _ezauthJsonConf$polic4;
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig.ui.step1.webAccessibilityText : {};
            var ezauthJsonConf = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf : {
                policys: {}
            };
            var setAttributes = function setAttributes(selector, titleKey, altKey) {
                var element = document.querySelector(selector);
                if (element) {
                    element.setAttribute("title", config[titleKey] || "");
                    element.setAttribute("alt", config[altKey] || "");
                }
            };
            var setPolicyAttributes = function setPolicyAttributes(policyId, title) {
                var element = document.querySelector("#EzauthContainer .body .step1 .policys #".concat(policyId, " img"));
                if (element) {
                    element.setAttribute("title", title || "");
                    element.setAttribute("alt", title || "");
                }
            };
            setAttributes("#EzauthContainer header .customer-logo-image > img", "headerLogo", "headerLogo");
            setAttributes("#EzauthContainer header .close > img", "closeButton", "closeButton");
            setAttributes("#EzauthContainer .body .step1 .ezauth-info .guide img", "guideText", "guideText");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.biz-registration-number input", "bizTitleBusinessRegistrationNumber", "bizTitleBusinessRegistrationNumber");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.name input", "bizTitleName", "bizTitleName");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.birth input", "bizTitleBirth", "bizTitleBirth");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.hp select", "bizTitleHPNumberPrefixSelect");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.hp input", "bizTitleHP", "bizTitleHP");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.ssn .ssn1", "bizTitleSsn1", "bizTitleSsn1");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.ssn .ssn2", "bizTitleSsn2", "bizTitleSsn2");
            setAttributes("#EzauthContainer .body .step1 .policys .policy-title .checkbox", "allAgree", "allAgree");
            setPolicyAttributes("policy1", (_ezauthJsonConf$polic = ezauthJsonConf.policys.policy1) === null || _ezauthJsonConf$polic === void 0 ? void 0 : _ezauthJsonConf$polic.title);
            setPolicyAttributes("policy2", (_ezauthJsonConf$polic2 = ezauthJsonConf.policys.policy2) === null || _ezauthJsonConf$polic2 === void 0 ? void 0 : _ezauthJsonConf$polic2.title);
            setPolicyAttributes("policy3", (_ezauthJsonConf$polic3 = ezauthJsonConf.policys.policy3) === null || _ezauthJsonConf$polic3 === void 0 ? void 0 : _ezauthJsonConf$polic3.title);
            setPolicyAttributes("policy4", (_ezauthJsonConf$polic4 = ezauthJsonConf.policys.policy4) === null || _ezauthJsonConf$polic4 === void 0 ? void 0 : _ezauthJsonConf$polic4.title);
            setAttributes("#EzauthContainer .body .step1 .policys .policy .button-show", "policyShowButton", "policyShowButton");
            setAttributes("#EzauthContainer .body .step1 .buttons .close", "closeButton", "closeButton");
            setAttributes("#EzauthContainer .body .step1 .buttons .req-auth", "reqAuthButton", "reqAuthButton");
            setAttributes("#EzauthContainer .body .step1 .buttons .complete-auth", "completeAuthButton", "completeAuthButton");
            setAttributes("#EzauthContainer .body .step2 .step-image .step01img .StepImg", "step01ImgText", "step01ImgText");
            setAttributes("#EzauthContainer .body .step2 .step-image .step02img .StepImg", "step02ImgText", "step02ImgText");
            setAttributes("#EzauthContainer .body .step2 .step-image .step03img .StepImg", "step03ImgText", "step03ImgText");
            setAttributes("#EzauthContainer .body .step2 .step-image .arrow", "progressArrow", "progressArrow");
            setAttributes("#EzauthContainer .body .step2 .buttons .close", "closeButton", "closeButton");
            setAttributes("#EzauthContainer .body .step2 .buttons .complete-auth", "completeAuthButton", "completeAuthButton");
        };
        child.makeEzauthUiStep1 = function(successGetGuiConf) {
            var _ezauthCore$ezauthJso3, _ezauthCore$ezauthJso4, _ezauthCore$ezauthJso5, _ezauthCore$jsonUiCon;
            var ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig.ui : {};
            var customerLogoImg = document.querySelector("#EzauthContainer header .header_logo_area > img");
            if (customerLogoImg) {
                var _ezauthCore$ezauthJso, _config$header, _config$header2;
                customerLogoImg.setAttribute("src", ((_ezauthCore$ezauthJso = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso === void 0 || (_ezauthCore$ezauthJso = _ezauthCore$ezauthJso.siteInfo) === null || _ezauthCore$ezauthJso === void 0 ? void 0 : _ezauthCore$ezauthJso.siteImgUrl) || "");
                customerLogoImg.setAttribute("alt", ((_config$header = config.header) === null || _config$header === void 0 ? void 0 : _config$header.logoText) || "");
                customerLogoImg.setAttribute("title", ((_config$header2 = config.header) === null || _config$header2 === void 0 ? void 0 : _config$header2.logoText) || "");
            }
            var headerTitle = document.querySelector("#EzauthContainer header .title");
            if (headerTitle) {
                var _ezauthCore$ezauthJso2;
                headerTitle.innerHTML = "| &nbsp ".concat(((_ezauthCore$ezauthJso2 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso2 === void 0 || (_ezauthCore$ezauthJso2 = _ezauthCore$ezauthJso2.siteInfo) === null || _ezauthCore$ezauthJso2 === void 0 ? void 0 : _ezauthCore$ezauthJso2.title) || "");
            }
            var closeImgHeader = document.querySelector("#EzauthContainer header .close img");
            if (closeImgHeader) {
                var _ezauthCore$basicInfo;
                closeImgHeader.setAttribute("src", ((_ezauthCore$basicInfo = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo === void 0 ? void 0 : _ezauthCore$basicInfo.ezauthRootPath) + "assets/img/icon_close_wht.png" || 0);
            }
            child.makeEzauthInit();
            var versionP = document.querySelector("#EzauthContainer .body .step1 .ezauth-info .version p");
            if (versionP) {
                var _config$step;
                versionP.textContent = ((_config$step = config.step1) === null || _config$step === void 0 || (_config$step = _config$step.sectionEzauthInfo) === null || _config$step === void 0 ? void 0 : _config$step.version) || "";
            }
            var guideP = document.querySelector("#EzauthContainer .body .step1 .ezauth-info .guide p");
            if (guideP) {
                var _config$step2;
                guideP.textContent = ((_config$step2 = config.step1) === null || _config$step2 === void 0 || (_config$step2 = _config$step2.sectionEzauthInfo) === null || _config$step2 === void 0 ? void 0 : _config$step2.guideText) || "";
            }
            var closeButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .close");
            if (closeButtonStep1) {
                var _config$step3;
                closeButtonStep1.textContent = ((_config$step3 = config.step1) === null || _config$step3 === void 0 || (_config$step3 = _config$step3.sectionButtons) === null || _config$step3 === void 0 ? void 0 : _config$step3.closeButton) || "";
            }
            var reqAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
            if (reqAuthButtonStep1) {
                var _config$step4;
                reqAuthButtonStep1.textContent = ((_config$step4 = config.step1) === null || _config$step4 === void 0 || (_config$step4 = _config$step4.sectionButtons) === null || _config$step4 === void 0 ? void 0 : _config$step4.reqAuthButton) || "";
            }
            var completeAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .complete-auth .label");
            if (completeAuthButtonStep1) {
                var _config$step5;
                completeAuthButtonStep1.textContent = ((_config$step5 = config.step1) === null || _config$step5 === void 0 || (_config$step5 = _config$step5.sectionButtons) === null || _config$step5 === void 0 ? void 0 : _config$step5.completeAuthButton) || "";
            }
            var guideElement = document.querySelector("#EzauthContainer .body .step1 .ezauth-info .guide");
            if (guideElement && (((_ezauthCore$ezauthJso3 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso3 === void 0 ? void 0 : _ezauthCore$ezauthJso3.guideShow) === undefined || ((_ezauthCore$ezauthJso4 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso4 === void 0 ? void 0 : _ezauthCore$ezauthJso4.guideShow) === null || !((_ezauthCore$ezauthJso5 = ezauthCore.ezauthJsonConf) !== null && _ezauthCore$ezauthJso5 !== void 0 && _ezauthCore$ezauthJso5.guideShow))) {
                guideElement.style.display = "none";
            }
            if (successGetGuiConf && (_ezauthCore$jsonUiCon = ezauthCore.jsonUiConf) !== null && _ezauthCore$jsonUiCon !== void 0 && _ezauthCore$jsonUiCon.uiConf) {
                var lastSelection = _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].getLocalStorageDataAsJSON("lastAuthSelection");
                var lastProviderId = lastSelection === null || lastSelection === void 0 ? void 0 : lastSelection.providerId;
                var lastAuthType = lastSelection === null || lastSelection === void 0 ? void 0 : lastSelection.authType;
                if (lastAuthType === "A2A") {
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = null;
                } else {
                    var _ezauthCore$jsonUiCon2;
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = ((ezauthCore === null || ezauthCore === void 0 || (_ezauthCore$jsonUiCon2 = ezauthCore.jsonUiConf) === null || _ezauthCore$jsonUiCon2 === void 0 || (_ezauthCore$jsonUiCon2 = _ezauthCore$jsonUiCon2.uiConf) === null || _ezauthCore$jsonUiCon2 === void 0 ? void 0 : _ezauthCore$jsonUiCon2.providerList) || []).find(function(p) {
                        return p.providerId === lastProviderId;
                    }) || null;
                }
                var providerList = JSON.parse(JSON.stringify(ezauthCore.jsonUiConf.uiConf.providerList));
                if (ezauthCore.jsonUiConf.uiConf.random === true) {
                    providerList = providerList.sort(function() {
                        return Math.random() - .5;
                    });
                }
                var index = providerList.findIndex(function(p) {
                    return p.providerId === lastProviderId;
                });
                if (index > 0) {
                    var _providerList$splice = providerList.splice(index, 1), _providerList$splice2 = (0, 
                    _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__["default"])(_providerList$splice, 1), target = _providerList$splice2[0];
                    providerList.unshift(target);
                }
                for (var i = 0; i < providerList.length; i++) {
                    child.makeEzauthUiProvider(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider, providerList[i]);
                }
                if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) {
                    var selectedProviderId = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerId;
                    var isProviderRendered = document.querySelector('#EzauthContainer .body .step1 .provider-list ul li[data-provider*="'.concat(selectedProviderId, '"]'));
                    if (!isProviderRendered) {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = null;
                    }
                }
                if (!_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) {
                    var dimmed = document.querySelector(".body .step1 .right .area_dimmed");
                    if (dimmed) {
                        dimmed.classList.add("active");
                    }
                } else {
                    var _dimmed = document.querySelector("#EzauthContainer .body .step1 .right .area_dimmed");
                    if (_dimmed) {
                        _dimmed.classList.remove("active");
                    }
                }
                var firstProvider = providerList[0];
                if (firstProvider) {
                    var providerId = firstProvider.providerId;
                    var pushInfo = document.querySelector(".body .step1 .user-info .push-info");
                    var policys = document.querySelector(".body .step1 .policys .encase");
                    var cloudInfo = document.querySelector(".cloud-info");
                    if (providerId === "kb" || providerId === "ibk") {
                        document.getElementById("cloud-btn").classList.add("on");
                        if (firstProvider.providerUseCloud === "Y") {
                            if (pushInfo) pushInfo.style.display = "none";
                            if (policys) policys.style.display = "none";
                            if (cloudInfo) cloudInfo.style.display = "block";
                        }
                    } else {
                        document.getElementById("cloud-btn").style.display = "none";
                        document.getElementById("app-push-btn").classList.add("on");
                        if (pushInfo) pushInfo.style.display = "block";
                        if (policys) policys.style.display = "block";
                        if (cloudInfo) cloudInfo.style.display = "none";
                    }
                }
                if (!_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) {
                    child.makeEzauthUiUserInfo(null);
                } else {
                    switch (lastAuthType) {
                      case "QR":
                        {
                            var qrButton = document.querySelector(".body .step1 .user-info .buttons .qr-button");
                            if (qrButton) qrButton.click();
                            break;
                        }

                      case "PUSH":
                        {
                            var pushButton = document.querySelector(".body .step1 .user-info .buttons .app-push-button");
                            if (pushButton) pushButton.click();
                            break;
                        }

                      case "CLOUD":
                        {
                            var cloudButton = document.querySelector(".body .step1 .user-info .buttons .cloud-button");
                            if (cloudButton) cloudButton.click();
                            break;
                        }

                      default:
                        child.makeEzauthUiUserInfo(null);
                        break;
                    }
                }
            } else {
                child.makeEzauthUiUserInfo(null);
            }
            var policyTitle = document.querySelector("#EzauthContainer .body .step1 .policys .policy-title .title");
            if (policyTitle) {
                var _ezauthCore$ezauthJso6;
                policyTitle.textContent = ((_ezauthCore$ezauthJso6 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso6 === void 0 || (_ezauthCore$ezauthJso6 = _ezauthCore$ezauthJso6.policys) === null || _ezauthCore$ezauthJso6 === void 0 ? void 0 : _ezauthCore$ezauthJso6.policyTitle) || "";
            }
            var allAgreeText = document.querySelector("#EzauthContainer .body .step1 .policys .policy-title .all-agree");
            if (allAgreeText) {
                var _config$step6;
                allAgreeText.textContent = ((_config$step6 = config.step1) === null || _config$step6 === void 0 || (_config$step6 = _config$step6.sectionPolicys) === null || _config$step6 === void 0 ? void 0 : _config$step6.allAgree) || "";
            }
            var policyShowButtons = document.querySelectorAll("#EzauthContainer .body .step1 .policys .body .button-show");
            if (policyShowButtons) {
                policyShowButtons.forEach(function(btn) {
                    var _config$step7;
                    btn.textContent = ((_config$step7 = config.step1) === null || _config$step7 === void 0 || (_config$step7 = _config$step7.sectionPolicys) === null || _config$step7 === void 0 ? void 0 : _config$step7.policyShowButton) || "";
                });
            }
            var policyIds = [ "policy1", "policy2", "policy3", "policy4" ];
            policyIds.forEach(function(id) {
                var _ezauthCore$ezauthJso7;
                var policyConfig = (_ezauthCore$ezauthJso7 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso7 === void 0 || (_ezauthCore$ezauthJso7 = _ezauthCore$ezauthJso7.policys) === null || _ezauthCore$ezauthJso7 === void 0 ? void 0 : _ezauthCore$ezauthJso7[id];
                var policyElement = document.getElementById(id);
                if (policyElement && policyConfig) {
                    var isRequired = policyConfig.required === "Y";
                    var isHidden = policyConfig.hidden === true;
                    if (!isRequired && !isHidden) {
                        isHidden = true;
                    }
                    if (!isRequired || isHidden) {
                        policyElement.style.display = "none";
                    } else {
                        var policyText = policyElement.querySelector(".text");
                        if (policyText) {
                            policyText.textContent = policyConfig.title || "";
                        }
                        policyElement.style.display = "flex";
                    }
                    policyElement.setAttribute("data-required", policyConfig.required);
                    policyElement.setAttribute("data-hidden", isHidden);
                }
            });
        };
        child.makeEzauthInit = function() {
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig : {};
            var ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
            var headerImg = document.querySelector("#EzauthContainer .body .step1 .user-info header img");
            if (headerImg) {
                var _config$ui, _config$ui2;
                headerImg.setAttribute("title", ((_config$ui = config.ui) === null || _config$ui === void 0 || (_config$ui = _config$ui.step1) === null || _config$ui === void 0 || (_config$ui = _config$ui.webAccessibilityText) === null || _config$ui === void 0 ? void 0 : _config$ui.certBiz) || "");
                headerImg.setAttribute("alt", ((_config$ui2 = config.ui) === null || _config$ui2 === void 0 || (_config$ui2 = _config$ui2.step1) === null || _config$ui2 === void 0 || (_config$ui2 = _config$ui2.webAccessibilityText) === null || _config$ui2 === void 0 ? void 0 : _config$ui2.certBiz) || "");
            }
            var headerP = document.querySelector("#EzauthContainer .body .step1 .user-info header p");
            if (headerP) {
                var _ezauthCore$ezauthJso8, _ezauthCore$ezauthJso9, _ezauthCore$ezauthJso0, _ezauthCore$ezauthJso1;
                if (((_ezauthCore$ezauthJso8 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso8 === void 0 ? void 0 : _ezauthCore$ezauthJso8.service) === "sign" || ((_ezauthCore$ezauthJso9 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso9 === void 0 ? void 0 : _ezauthCore$ezauthJso9.service) === "signBiz") {
                    var _config$ui3;
                    headerP.textContent = ((_config$ui3 = config.ui) === null || _config$ui3 === void 0 || (_config$ui3 = _config$ui3.step1) === null || _config$ui3 === void 0 || (_config$ui3 = _config$ui3.sectionUserInfo) === null || _config$ui3 === void 0 ? void 0 : _config$ui3.signTitleText) || "";
                } else if (((_ezauthCore$ezauthJso0 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso0 === void 0 ? void 0 : _ezauthCore$ezauthJso0.service) === "auth" || ((_ezauthCore$ezauthJso1 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso1 === void 0 ? void 0 : _ezauthCore$ezauthJso1.service) === "authBiz") {
                    var _config$ui4;
                    headerP.textContent = ((_config$ui4 = config.ui) === null || _config$ui4 === void 0 || (_config$ui4 = _config$ui4.step1) === null || _config$ui4 === void 0 || (_config$ui4 = _config$ui4.sectionUserInfo) === null || _config$ui4 === void 0 ? void 0 : _config$ui4.authTitleText) || "";
                }
            }
            var bizRegNumLi = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.biz-registration-number");
            if (bizRegNumLi) {
                var _ezauthCore$ezauthJso10;
                if (!(((_ezauthCore$ezauthJso10 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso10 === void 0 || (_ezauthCore$ezauthJso10 = _ezauthCore$ezauthJso10.service) === null || _ezauthCore$ezauthJso10 === void 0 ? void 0 : _ezauthCore$ezauthJso10.toLowerCase().indexOf("biz")) < 0)) {
                    var _config$ui5, _config$ui6;
                    var label = bizRegNumLi.querySelector(".label");
                    if (label) label.textContent = ((_config$ui5 = config.ui) === null || _config$ui5 === void 0 || (_config$ui5 = _config$ui5.step1) === null || _config$ui5 === void 0 || (_config$ui5 = _config$ui5.sectionUserInfo) === null || _config$ui5 === void 0 ? void 0 : _config$ui5.bizTitleBusinessRegistrationNumber) || "";
                    var input = bizRegNumLi.querySelector("input");
                    if (input) input.setAttribute("placeholder", ((_config$ui6 = config.ui) === null || _config$ui6 === void 0 || (_config$ui6 = _config$ui6.step1) === null || _config$ui6 === void 0 || (_config$ui6 = _config$ui6.sectionUserInfo) === null || _config$ui6 === void 0 ? void 0 : _config$ui6.bizTitleBusinessRegistrationNumberPlaceholder) || "");
                }
            }
            var nameLi = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.name");
            if (nameLi) {
                var _config$ui7, _config$ui8;
                var _label3 = nameLi.querySelector(".label");
                if (_label3) _label3.textContent = ((_config$ui7 = config.ui) === null || _config$ui7 === void 0 || (_config$ui7 = _config$ui7.step1) === null || _config$ui7 === void 0 || (_config$ui7 = _config$ui7.sectionUserInfo) === null || _config$ui7 === void 0 ? void 0 : _config$ui7.bizTitleName) || "";
                var _input = nameLi.querySelector("input");
                if (_input) _input.setAttribute("placeholder", ((_config$ui8 = config.ui) === null || _config$ui8 === void 0 || (_config$ui8 = _config$ui8.step1) === null || _config$ui8 === void 0 || (_config$ui8 = _config$ui8.sectionUserInfo) === null || _config$ui8 === void 0 ? void 0 : _config$ui8.bizTitleNamePlaceholder) || "");
                nameLi.style.display = "flex";
            }
            var birthLi = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.birth");
            if (birthLi) {
                var _config$ui9, _config$ui0;
                var _label4 = birthLi.querySelector(".label");
                if (_label4) _label4.textContent = ((_config$ui9 = config.ui) === null || _config$ui9 === void 0 || (_config$ui9 = _config$ui9.step1) === null || _config$ui9 === void 0 || (_config$ui9 = _config$ui9.sectionUserInfo) === null || _config$ui9 === void 0 ? void 0 : _config$ui9.bizTitleBirth) || "";
                var _input2 = birthLi.querySelector("input");
                if (_input2) _input2.setAttribute("placeholder", ((_config$ui0 = config.ui) === null || _config$ui0 === void 0 || (_config$ui0 = _config$ui0.step1) === null || _config$ui0 === void 0 || (_config$ui0 = _config$ui0.sectionUserInfo) === null || _config$ui0 === void 0 ? void 0 : _config$ui0.bizTitleBirthPlaceholder) || "");
                birthLi.style.display = "flex";
            }
            var hpLi = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp");
            if (hpLi) {
                var _config$ui1, _config$ui10;
                hpLi.style.display = "flex";
                var _label5 = hpLi.querySelector(".label");
                if (_label5) _label5.textContent = ((_config$ui1 = config.ui) === null || _config$ui1 === void 0 || (_config$ui1 = _config$ui1.step1) === null || _config$ui1 === void 0 || (_config$ui1 = _config$ui1.sectionUserInfo) === null || _config$ui1 === void 0 ? void 0 : _config$ui1.bizTitleHP) || "";
                var hpInputInner = hpLi.querySelector("input");
                if (hpInputInner) hpInputInner.setAttribute("placeholder", ((_config$ui10 = config.ui) === null || _config$ui10 === void 0 || (_config$ui10 = _config$ui10.step1) === null || _config$ui10 === void 0 || (_config$ui10 = _config$ui10.sectionUserInfo) === null || _config$ui10 === void 0 ? void 0 : _config$ui10.bizTitleHPPlaceholder) || "");
            }
            var ssnLi = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.ssn");
            if (ssnLi) {
                var _config$ui11;
                var _label6 = ssnLi.querySelector(".label");
                if (_label6) _label6.textContent = ((_config$ui11 = config.ui) === null || _config$ui11 === void 0 || (_config$ui11 = _config$ui11.step1) === null || _config$ui11 === void 0 || (_config$ui11 = _config$ui11.sectionUserInfo) === null || _config$ui11 === void 0 ? void 0 : _config$ui11.bizTitleSsn) || "";
                var ssn1 = ssnLi.querySelector(".ssn1");
                if (ssn1) {
                    var _config$ui12;
                    ssn1.setAttribute("placeholder", ((_config$ui12 = config.ui) === null || _config$ui12 === void 0 || (_config$ui12 = _config$ui12.step1) === null || _config$ui12 === void 0 || (_config$ui12 = _config$ui12.sectionUserInfo) === null || _config$ui12 === void 0 ? void 0 : _config$ui12.bizTitleSsn1Placeholder) || "");
                    ssn1.style.width = "145px";
                    ssn1.style.display = "flex";
                }
                var ssn2 = ssnLi.querySelector(".ssn2");
                if (ssn2) {
                    var _config$ui13;
                    ssn2.setAttribute("placeholder", ((_config$ui13 = config.ui) === null || _config$ui13 === void 0 || (_config$ui13 = _config$ui13.step1) === null || _config$ui13 === void 0 || (_config$ui13 = _config$ui13.sectionUserInfo) === null || _config$ui13 === void 0 ? void 0 : _config$ui13.bizTitleSsn2Placeholder) || "");
                    ssn2.style.width = "145px";
                    ssn2.style.display = "flex";
                }
            }
        };
        child.makeEzauthUiProvider = function(lastSelectedProvider, provider) {
            var _ezauthCore$ezauthJso12, _ezauthCore$ezauthJso13, _ezauthCore$ezauthJso14;
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig : {};
            var ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
            if (ezauthCore.ezauthJsonConf.service === "authBiz") {
                sessionStorage.removeItem("EZAuth");
            }
            var i = 0;
            var serviceFound = false;
            if (provider.service) {
                for (i = 0; i < provider.service.length; i++) {
                    var _ezauthCore$ezauthJso11;
                    if (((_ezauthCore$ezauthJso11 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso11 === void 0 ? void 0 : _ezauthCore$ezauthJso11.service) === provider.service[i]) {
                        serviceFound = true;
                        break;
                    }
                }
            }
            if (!serviceFound) {
                return;
            }
            if (((_ezauthCore$ezauthJso12 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso12 === void 0 || (_ezauthCore$ezauthJso12 = _ezauthCore$ezauthJso12.service) === null || _ezauthCore$ezauthJso12 === void 0 ? void 0 : _ezauthCore$ezauthJso12.toLowerCase().indexOf("biz")) < 0) {
                var _config$inputtype;
                if (provider.inputtype === undefined || provider.inputtype === null || ((_config$inputtype = config.inputtype) === null || _config$inputtype === void 0 ? void 0 : _config$inputtype[provider.inputtype]) === undefined) {
                    return;
                }
            } else {
                var _config$inputtypebiz;
                if (provider.inputtypebiz === undefined || provider.inputtypebiz === null || ((_config$inputtypebiz = config.inputtypebiz) === null || _config$inputtypebiz === void 0 ? void 0 : _config$inputtypebiz[provider.inputtypebiz]) === undefined) {
                    return;
                }
            }
            if (((_ezauthCore$ezauthJso13 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso13 === void 0 ? void 0 : _ezauthCore$ezauthJso13.service) === "sign" || ((_ezauthCore$ezauthJso14 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso14 === void 0 ? void 0 : _ezauthCore$ezauthJso14.service) === "signBiz") {
                var _ezauthCore$ezauthJso15, _ezauthCore$basicInfo2, _ezauthCore$basicInfo3, _ezauthCore$basicInfo4, _ezauthCore$ezauthJso16, _ezauthCore$ezauthJso17, _ezauthCore$ezauthJso18, _ezauthCore$basicInfo5, _ezauthCore$basicInfo6, _ezauthCore$basicInfo7, _ezauthCore$basicInfo8;
                var signType = (_ezauthCore$ezauthJso15 = ezauthCore.ezauthJsonConf.signInfo) === null || _ezauthCore$ezauthJso15 === void 0 ? void 0 : _ezauthCore$ezauthJso15.signType;
                if (((_ezauthCore$basicInfo2 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo2 === void 0 ? void 0 : _ezauthCore$basicInfo2.signType) !== undefined && ((_ezauthCore$basicInfo3 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo3 === void 0 ? void 0 : _ezauthCore$basicInfo3.signType) !== null && ((_ezauthCore$basicInfo4 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo4 === void 0 ? void 0 : _ezauthCore$basicInfo4.signType) !== "") {
                    signType = ezauthCore.basicInfo.signType;
                }
                var signTypeFound = false;
                if (provider.signType) {
                    for (i = 0; i < provider.signType.length; i++) {
                        if (signType === provider.signType[i]) {
                            signTypeFound = true;
                            break;
                        }
                    }
                }
                if (!signTypeFound) {
                    return;
                }
                var signPKCSType = "PKCS7";
                if (((_ezauthCore$ezauthJso16 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso16 === void 0 ? void 0 : _ezauthCore$ezauthJso16.signPKCSType) !== undefined && ((_ezauthCore$ezauthJso17 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso17 === void 0 ? void 0 : _ezauthCore$ezauthJso17.signPKCSType) !== null && ((_ezauthCore$ezauthJso18 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso18 === void 0 ? void 0 : _ezauthCore$ezauthJso18.signPKCSType) !== "") {
                    signPKCSType = ezauthCore.ezauthJsonConf.signPKCSType;
                }
                if (((_ezauthCore$basicInfo5 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo5 === void 0 ? void 0 : _ezauthCore$basicInfo5.signPKCSType) !== undefined && ((_ezauthCore$basicInfo6 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo6 === void 0 ? void 0 : _ezauthCore$basicInfo6.signPKCSType) !== null && ((_ezauthCore$basicInfo7 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo7 === void 0 ? void 0 : _ezauthCore$basicInfo7.signPKCSType) !== "") {
                    signPKCSType = ezauthCore.basicInfo.signPKCSType;
                }
                var signPKCSTypeFound = false;
                if (provider.signPKCSType) {
                    for (i = 0; i < provider.signPKCSType.length; i++) {
                        if (signPKCSType === provider.signPKCSType[i]) {
                            signPKCSTypeFound = true;
                            break;
                        }
                    }
                }
                if (!signPKCSTypeFound) {
                    return;
                }
                if (((_ezauthCore$basicInfo8 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo8 === void 0 || (_ezauthCore$basicInfo8 = _ezauthCore$basicInfo8.signContents) === null || _ezauthCore$basicInfo8 === void 0 ? void 0 : _ezauthCore$basicInfo8.length) > 1) {
                    if (provider.multi_sign !== "Y") {
                        return;
                    }
                }
            }
            var isMatched = (lastSelectedProvider === null || lastSelectedProvider === void 0 ? void 0 : lastSelectedProvider.providerId) === provider.providerId;
            var providerStr = "\n            <li".concat(isMatched ? ' class="on"' : "", '>\n                <button \n                    type="button" \n                    style="background: none; border: none; padding: 0; cursor: pointer;"\n                    aria-pressed="').concat(isMatched, '" \n                    aria-label="').concat(provider.providerName, " ").concat(isMatched ? "선택됨" : "선택되지 않음", '"\n                >\n                    <img src="" title="').concat(provider.providerName, '" alt="').concat(provider.providerName, '">\n                    ').concat(isMatched ? '<span class="badge badge_recent">최근</span>' : "", "\n                </button>\n                <p>").concat(provider.providerName, "</p>\n            </li>\n        ");
            var tempDiv = document.createElement("div");
            tempDiv.innerHTML = providerStr.trim();
            var providerObj = tempDiv.firstChild;
            var providersConfig = config.provider || [];
            var selectedProviderConfig = providersConfig.find(function(item) {
                return item.id === provider.providerId;
            });
            if (selectedProviderConfig) {
                var imgElement = providerObj.querySelector("img");
                if (imgElement) {
                    var _ezauthCore$basicInfo9;
                    imgElement.setAttribute("src", ((_ezauthCore$basicInfo9 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo9 === void 0 ? void 0 : _ezauthCore$basicInfo9.ezauthRootPath) + selectedProviderConfig.imgPath || "");
                }
            }
            providerObj.dataset.provider = JSON.stringify(provider);
            var providerListUl = document.querySelector("#EzauthContainer .body .step1 .provider-list ul");
            if (providerListUl) {
                providerListUl.appendChild(providerObj);
            }
            var _targetElement = document.querySelector(".provider-list li:first-child button");
            if (_targetElement) {
                _targetElement.focus();
                window.focus();
            }
        };
        child.cleanEzauthUiProvider = function() {
            var providers = document.querySelectorAll("#EzauthContainer .body .step1 .provider-list ul li");
            if (providers && providers.length > 0) {
                providers.forEach(function(provider) {
                    return provider.remove();
                });
            }
        };
        child.makeEzauthUiUserInfo = function(provider) {
            var _EzauthCore$basicInfo2;
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig : {};
            var pushInfo = document.querySelector(".body .step1 .user-info .push-info");
            var pushTitle = document.querySelector(".body .step1 .user-info .push-info .push-info-title");
            var policys = document.querySelector(".body .step1 .policys .encase");
            var cloudInfo = document.querySelector(".cloud-info");
            var cloudBtn = document.getElementById("cloud-btn");
            if (cloudBtn.classList.contains("on")) {
                if (pushInfo) pushInfo.style.display = "none";
                if (policys) policys.style.display = "none";
                if (cloudInfo) cloudInfo.style.display = "block";
            } else {
                if (pushInfo) pushInfo.style.display = "block";
                if (pushTitle) pushTitle.style.display = "block";
                if (policys) policys.style.display = "block";
                if (cloudInfo) cloudInfo.style.display = "none";
            }
            var reqAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
            if (reqAuthButtonStep1) reqAuthButtonStep1.style.display = "";
            var completeAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .complete-auth");
            if (completeAuthButtonStep1) completeAuthButtonStep1.style.display = "none";
            if (provider !== null) {
                provider.providerUseCloud === "Y" ? document.getElementById("cloud-btn").style.display = "block" : document.getElementById("cloud-btn").style.display = "none";
            }
            var qrSection = document.querySelector("#EzauthContainer .body .step1 .user-info .qr-info .qr-code-section");
            if (qrSection) qrSection.style.display = "none";
            var policySection = document.querySelector("#EzauthContainer .body .step1 .policys");
            if (policySection) policySection.style.display = "";
            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].cleanUserInfo();
            if (provider && ((_EzauthCore$basicInfo2 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) !== null && _EzauthCore$basicInfo2 !== void 0 && _EzauthCore$basicInfo2.userInfo || sessionStorage.getItem("EZAuth") != null)) {
                child.getDecInputData();
            }
            if (provider !== null) {
                var _config$ui14, _EzauthCore$ezauthJso2;
                var providerObj = document.querySelector("#EzauthContainer .body .step1 .provider-list li.on");
                if (!providerObj) return;
                var headerImg = document.querySelector("#EzauthContainer .body .step1 .user-info header img");
                var providerImg = providerObj.querySelector("img");
                if (headerImg && providerImg) {
                    headerImg.setAttribute("src", providerImg.getAttribute("src") || "");
                    headerImg.setAttribute("title", providerImg.getAttribute("title") || "");
                    headerImg.setAttribute("alt", providerImg.getAttribute("alt") || "");
                }
                var headerP = document.querySelector("#EzauthContainer .body .step1 .user-info header p");
                var providerP = providerObj.querySelector("p");
                if (headerP && providerP && (_config$ui14 = config.ui) !== null && _config$ui14 !== void 0 && (_config$ui14 = _config$ui14.step1) !== null && _config$ui14 !== void 0 && _config$ui14.sectionUserInfo) {
                    headerP.textContent = providerP.textContent || "";
                }
                document.querySelectorAll("#EzauthContainer .body .step1 .user-info .body li").forEach(function(li) {
                    li.style.display = "none";
                });
                var hpTelcoSelect = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp .telco");
                if (hpTelcoSelect) hpTelcoSelect.style.display = "none";
                var selectedInputtype;
                if (((_EzauthCore$ezauthJso2 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf) === null || _EzauthCore$ezauthJso2 === void 0 || (_EzauthCore$ezauthJso2 = _EzauthCore$ezauthJso2.service) === null || _EzauthCore$ezauthJso2 === void 0 ? void 0 : _EzauthCore$ezauthJso2.toLowerCase().indexOf("biz")) < 0) {
                    var _config$inputtype2;
                    selectedInputtype = (_config$inputtype2 = config.inputtype) === null || _config$inputtype2 === void 0 ? void 0 : _config$inputtype2[provider.inputtype];
                } else {
                    var _config$inputtypebiz2;
                    selectedInputtype = (_config$inputtypebiz2 = config.inputtypebiz) === null || _config$inputtypebiz2 === void 0 ? void 0 : _config$inputtypebiz2[provider.inputtypebiz];
                }
                if (!selectedInputtype) return;
                for (var i = 0; i < selectedInputtype.length; i++) {
                    var inputTypeName = selectedInputtype[i];
                    if (inputTypeName === "사업자등록번호") {
                        var el = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.biz-registration-number");
                        if (el) el.style.display = "flex";
                    } else if (inputTypeName === "이름") {
                        var _el = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.name");
                        if (_el) _el.style.display = "flex";
                    } else if (inputTypeName === "생년월일") {
                        var _el2 = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.birth");
                        if (_el2) _el2.style.display = "flex";
                    } else if (inputTypeName === "통신사") {
                        var hpTelco = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp .telco");
                        var hpInput = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp input");
                        if (hpTelco) hpTelco.style.width = "100px";
                        if (hpInput) hpInput.style.width = "185px";
                        if (hpTelco) hpTelco.style.display = "flex";
                    } else if (inputTypeName === "전화번호") {
                        var hpLiElement = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp");
                        var _hpInput = hpLiElement ? hpLiElement.querySelector("input") : null;
                        var _hpTelco = hpLiElement ? hpLiElement.querySelector(".telco") : null;
                        if (_hpTelco && _hpTelco.style.display === "none") {
                            if (_hpInput) _hpInput.style.width = "316px";
                        } else {
                            if (_hpTelco) _hpTelco.style.width = "100px";
                            if (_hpInput) _hpInput.style.width = "185px";
                        }
                        if (hpLiElement) hpLiElement.style.display = "flex";
                    } else if (inputTypeName === "주민번호 앞자리") {
                        var ssnLiElement = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.ssn");
                        var ssn1 = ssnLiElement ? ssnLiElement.querySelector(".ssn1") : null;
                        var ssn2 = ssnLiElement ? ssnLiElement.querySelector(".ssn2") : null;
                        if (ssn1 && ssn1.style.display === "none") {
                            if (ssn2) ssn2.style.width = "316px";
                        } else {
                            if (ssn1) ssn1.style.width = "145px";
                            if (ssn2) ssn2.style.width = "145px";
                        }
                        if (ssnLiElement) {
                            ssnLiElement.style.display = "flex";
                        }
                    } else if (inputTypeName === "주민번호 뒷자리") {
                        var _ssnLiElement = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.ssn");
                        var _ssn = _ssnLiElement ? _ssnLiElement.querySelector(".ssn1") : null;
                        var _ssn2 = _ssnLiElement ? _ssnLiElement.querySelector(".ssn2") : null;
                        if (_ssn2 && _ssn2.style.display === "none") {
                            if (_ssn) _ssn.style.width = "316px";
                        } else {
                            if (_ssn) _ssn.style.width = "145px";
                            if (_ssn2) _ssn2.style.width = "145px";
                        }
                        if (_ssnLiElement) {
                            _ssnLiElement.style.display = "flex";
                        }
                    }
                }
                var listItems = document.querySelectorAll("#EzauthContainer .body .step1 .user-info li");
                var _iterator2 = _createForOfIteratorHelper(listItems), _step2;
                try {
                    for (_iterator2.s(); !(_step2 = _iterator2.n()).done; ) {
                        var li = _step2.value;
                        if (window.getComputedStyle(li).display !== "none") {
                            var input = li.querySelector("input:not([type='hidden'])");
                            if (input) {
                                input.focus();
                                break;
                            }
                        }
                    }
                } catch (err) {
                    _iterator2.e(err);
                } finally {
                    _iterator2.f();
                }
                var dimmed = document.querySelector(".body .step1 .right .area_dimmed");
                if (provider.maintenanceYN === "Y") {
                    _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(provider.maintenanceNotice);
                    dimmed === null || dimmed === void 0 || dimmed.classList.add("active");
                } else {
                    dimmed === null || dimmed === void 0 || dimmed.classList.remove("active");
                }
            }
        };
        child.makeEzauthUiQRInfo = function(provider) {
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig : {};
            var reqAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
            if (reqAuthButtonStep1) reqAuthButtonStep1.style.display = "none";
            var completeAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .complete-auth");
            if (completeAuthButtonStep1) completeAuthButtonStep1.style.display = "";
            var pushTitle = document.querySelector("#EzauthContainer .body .step1 .user-info .push-info .push-info-title");
            if (pushTitle) pushTitle.style.display = "none";
            var cloudInfo = document.querySelector("#EzauthContainer .body .step1 .user-info .cloud-info");
            if (cloudInfo) cloudInfo.style.display = "none";
            if (provider !== null) {
                provider.providerUseCloud === "Y" ? document.getElementById("cloud-btn").style.display = "block" : document.getElementById("cloud-btn").style.display = "none";
            }
            if (provider !== null) {
                var _config$ui15;
                var userInfoEl = document.querySelector("#EzauthContainer .body .step1 .user-info .body");
                if (userInfoEl) {
                    Array.from(userInfoEl.children).forEach(function(child) {
                        child.style.display = "none";
                    });
                }
                var qrCodeSection = document.querySelector("#EzauthContainer .body .step1 .user-info .qr-info .qr-code-section");
                if (qrCodeSection) qrCodeSection.style.display = "flex";
                var policySection = document.querySelector("#EzauthContainer .body .step1 .policys");
                if (policySection) policySection.style.display = "none";
                var providerObj = document.querySelector("#EzauthContainer .body .step1 .provider-list li.on");
                if (!providerObj) return;
                var headerImg = document.querySelector("#EzauthContainer .body .step1 .user-info header img");
                var providerImg = providerObj.querySelector("img");
                if (headerImg && providerImg) {
                    headerImg.setAttribute("src", providerImg.getAttribute("src") || "");
                    headerImg.setAttribute("title", providerImg.getAttribute("title") || "");
                    headerImg.setAttribute("alt", providerImg.getAttribute("alt") || "");
                }
                var headerP = document.querySelector("#EzauthContainer .body .step1 .user-info header p");
                var providerP = providerObj.querySelector("p");
                if (headerP && providerP && (_config$ui15 = config.ui) !== null && _config$ui15 !== void 0 && (_config$ui15 = _config$ui15.step1) !== null && _config$ui15 !== void 0 && _config$ui15.sectionUserInfo) {
                    headerP.textContent = providerP.textContent || "";
                }
            }
        };
        child.makeEzauthUiStep2 = function(result) {
            var _dynamicConfigs$provi, _config$step8, _config$step9, _config$step0, _EzauthCore$ezauthJso3, _EzauthCore$jsonSelec, _EzauthCore$jsonSelec2, _config$step12, _config$step13;
            var providerId = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerId;
            var authType = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType;
            var dynamicConfigs = {
                kakaobank: {
                    PUSH: {
                        infoQuestion: "문제 발생 시 조치방법",
                        infoAnswer1: '카카오뱅크 사업자 인증서 이용에 문제가 있는 경우,카카오뱅크 앱을 최신버전으로 업데이트 하시거나, <br>\n                    <a href="https://www.kakaobank.com/Help/Guide/Faq#%EB%B3%B4%EC%95%88%EC%84%A4%EC%A0%95" style="color:black;" target="_blank"><strong>[고객센터 자주묻는질문]</strong></a> 에서 해결방법을 찾아보세요.',
                        infoAnswer2: '문제가 지속되면 <a href="https://www.kakaobank.com/Help/Consult/Online/Ask"  style="color:black;" target="_blank"><strong>[고객센터 1:1문의]</strong></a>를 통해 문의 주시거나, \n                    카카오뱅크 고객센터(☎️ 1599-3333)로 연락해 주세요.'
                    }
                },
                kb: {
                    PUSH: {
                        infoQuestion: "혹시 KB스타기업뱅킹 앱 PUSH 알림이 오지 않으시나요?",
                        infoAnswer1: "<span style='color:#009dce; font-weight:500'>KB스타기업뱅킹 앱 로그인 > 알림함(종 모양)> 알림설정</span>에서 PUSH 알림을 동의 해주세요.",
                        infoAnswer2: "휴대폰 설정에 KB스타기업뱅킹 알림'허용'으로 등록해주세요.",
                        infoQuestion2: "그래도 KB스타기업뱅킹 앱 PUSH가 오지 않으시나요?",
                        infoAnswerkb: "<span style='color:#009dce;font-weight:500'>KB스타기업뱅킹 앱 > 인증센터 > KB국민인증서(기업) > 인증요청내역</span>을 통해 인증해주세요.",
                        kbCall: "※ KB국민은행 고객센터: 1588-9999"
                    },
                    CLOUD: {
                        stepInfo1: "<strong>STEP 01</strong> 클라우드 인증창에서 로그인",
                        stepInfo2: "<strong>STEP 02</strong> 간편인증 (비밀번호 등)",
                        stepInfo3: "<strong>STEP 03</strong> 인증완료 후, 하단의 인증완료<br>클릭",
                        infoQuestion: "<strong>클라우드 인증창이 뜨지 않나요?</strong>",
                        infoAnswer: "입력하신 정보를 확인하신 후 다시 시도해주세요.",
                        kbCall: "※ KB국민은행 고객센터: 1588-9999"
                    }
                },
                ibk: {
                    PUSH: {
                        infoAnswer1: "<strong>[i-ONE Bank(기업)앱]</strong> 설치가 휴대폰에 되어 있는지 확인해주세요.",
                        infoAnswer2: "<strong>[i-ONE Bank(기업)앱 > 인증ᆞ보안 > IBK인증서 > 인증알림]</strong> 에서 인증요청 내용을 확인할 수 있습니다.",
                        infoAnswer3: "APP PUSH 수신여부가 비활성화 되어있거나 APP 미설치 시, SMS로 발송됩니다.",
                        infoAnswer4: "<strong>[i-ONE Bank(기업)앱 > 인증ᆞ보안 > IBK인증서 > 인증알림 > PUSH 설정]</strong> 에서 PUSH 알림 설정을 변경할 수 있습니다.",
                        infoAnswer5: "문제가 계속된다면, <strong>[IBK기업은행 고객센터 1588-2588, 1566-2566]</strong>로 문의 부탁드립니다."
                    },
                    CLOUD: {
                        stepInfo1: "<strong>STEP 01</strong> 클라우드 인증창에서 로그인",
                        stepInfo2: "<strong>STEP 02</strong> 간편인증 (비밀번호 등)",
                        stepInfo3: "<strong>STEP 03</strong> 인증완료 후, 하단의 인증완료<br>클릭",
                        infoAnswer1: "<strong>[IBK인증서 팝업창]</strong>이 열리지 않았다면 아래 '확인'버튼을 클릭해서 인증을 진행해 주세요.",
                        infoAnswer2: "문제가 계속된다면, 앱을 최신버전으로 업데이트 하시거나, <strong>[IBK기업은행 고객센터 1588-2588, 1566-2566]</strong>로 문의 부탁드립니다."
                    }
                }
            };
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig.ui : {};
            var dynamicConfig = (_dynamicConfigs$provi = dynamicConfigs[providerId]) === null || _dynamicConfigs$provi === void 0 ? void 0 : _dynamicConfigs$provi[authType];
            var setTextContent = function setTextContent(selector, text) {
                var el = document.querySelector(selector);
                if (el) el.textContent = text || "";
            };
            var setInnerHTML = function setInnerHTML(selector, html) {
                var el = document.querySelector(selector);
                if (el) el.innerHTML = html || "";
            };
            var setAttribute = function setAttribute(selector, attr, value) {
                if (selector) selector.setAttribute(attr, value || "");
            };
            setInnerHTML("#EzauthContainer .body .step2 .text", (_config$step8 = config.step2) === null || _config$step8 === void 0 ? void 0 : _config$step8.titleText);
            setInnerHTML("#EzauthContainer .body .step2 .text-detail", authType === "CLOUD" ? (_config$step9 = config.step2) === null || _config$step9 === void 0 ? void 0 : _config$step9.titleCloudTextDetail : (_config$step0 = config.step2) === null || _config$step0 === void 0 ? void 0 : _config$step0.titleTextDetail);
            var step2Name = document.querySelector("#EzauthContainer .body .step2 .header .center .step2_name");
            if (step2Name && (_EzauthCore$ezauthJso3 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf) !== null && _EzauthCore$ezauthJso3 !== void 0 && (_EzauthCore$ezauthJso3 = _EzauthCore$ezauthJso3.siteInfo) !== null && _EzauthCore$ezauthJso3 !== void 0 && _EzauthCore$ezauthJso3.title) {
                step2Name.textContent = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf.siteInfo.title;
            }
            var providerNameSpan = document.querySelector("#EzauthContainer .body .step2 .header .left .step2_provider_name");
            if (providerNameSpan && (_EzauthCore$jsonSelec = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) !== null && _EzauthCore$jsonSelec !== void 0 && _EzauthCore$jsonSelec.providerName) {
                providerNameSpan.textContent = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerName;
            }
            var step2HeaderImg = document.querySelector("#EzauthContainer .body .step2 .header .left img");
            if (step2HeaderImg && (_EzauthCore$jsonSelec2 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) !== null && _EzauthCore$jsonSelec2 !== void 0 && _EzauthCore$jsonSelec2.providerName) {
                var providers = window.parent.EzauthConfig.provider;
                var providerInfo = providers.find(function(p) {
                    return p.name === _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerName;
                });
                if (providerInfo) {
                    step2HeaderImg.setAttribute("src", providerInfo.imgPath);
                    step2HeaderImg.setAttribute("alt", "".concat(providerInfo.name, " 로고"));
                }
            }
            var step2HeaderLogoImg = document.querySelector("#EzauthContainer .body .step2 .header .right img");
            if (step2HeaderLogoImg) {
                var _EzauthCore$ezauthJso4, _config$header3, _config$header4;
                step2HeaderLogoImg.setAttribute("src", ((_EzauthCore$ezauthJso4 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf) === null || _EzauthCore$ezauthJso4 === void 0 || (_EzauthCore$ezauthJso4 = _EzauthCore$ezauthJso4.siteInfo) === null || _EzauthCore$ezauthJso4 === void 0 ? void 0 : _EzauthCore$ezauthJso4.siteImgUrl) || "");
                step2HeaderLogoImg.setAttribute("alt", ((_config$header3 = config.header) === null || _config$header3 === void 0 ? void 0 : _config$header3.logoText) || "");
                step2HeaderLogoImg.setAttribute("title", ((_config$header4 = config.header) === null || _config$header4 === void 0 ? void 0 : _config$header4.logoText) || "");
            }
            var providerImgStep2 = document.querySelector("#EzauthContainer .body .step2 img.provider");
            var headerImgStep1 = document.querySelector("#EzauthContainer .body .step1 .user-info header img");
            if (providerImgStep2 && headerImgStep1) {
                setAttribute(providerImgStep2, "src", headerImgStep1.getAttribute("src"));
            }
            var step01Img = document.querySelector("#EzauthContainer .body .step2 .middle .step01img img.StepImg");
            if (step01Img) {
                var defaultImg = "assets/img/pc_step_01_nor.svg";
                step01Img.src = dynamicConfigs[providerId] ? "assets/img/pc_step_01_".concat(providerId, ".svg") : defaultImg;
            }
            var generateInfoDetailHTML = function generateInfoDetailHTML() {
                var html = "";
                if (!dynamicConfig) {
                    if (authType === "PUSH") {
                        html += '\n                        <div class="question">휴대폰에 인증요청 알림이 오지 않나요?</div>\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">1</span>\n                                <p>해당 앱 설치 및 로그인 여부를 확인하세요.</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">2</span>\n                                <p>앱 알림 수신동의가 되어있는지 확인해주세요.</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">3</span>\n                                <p>문제가 지속되면 도움말/ 이메일문의를 통해 문의해주세요.</p>\n                            </li>\n                        </ul>\n                    ';
                    } else if (authType === "CLOUD") {
                        setInnerHTML("#EzauthContainer .body .step2 .step-info div:nth-child(1)", "<strong>STEP 01</strong> 클라우드 인증창에서 로그인");
                        html += '\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <p style="color: black; font-weight: 500;">클라우드 인증창이 뜨지 않나요?</p>\n                            </li>\n                            <p style="margin-top: 10px; color: #666;">입력하신 정보를 확인하신 후 다시 시도해주세요.</p>\n                        </ul>\n                        \n                    ';
                    }
                    return html;
                }
                if (dynamicConfig.infoQuestion) {
                    var questionStyle = providerId === "kb" && authType === "CLOUD" ? 'style="padding-bottom: 0; padding-top: 13px;"' : "";
                    html += '<div class="question" '.concat(questionStyle, ">").concat(dynamicConfig.infoQuestion, "</div>");
                }
                if (providerId === "kb") {
                    if (authType === "PUSH") {
                        html += '\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">1</span>\n                                <p>'.concat(dynamicConfig.infoAnswer1, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">2</span>\n                                <p>').concat(dynamicConfig.infoAnswer2, "</p>\n                            </li>\n                        </ul>\n                    ");
                        if (dynamicConfig.infoQuestion2) {
                            html += '\n                            <div class="question" style="margin-top: 20px;">'.concat(dynamicConfig.infoQuestion2, '</div>\n                            <ul class="question-info">\n                                <li class="num-text">\n                                    <p>').concat(dynamicConfig.infoAnswerkb, "</p>\n                                </li>\n                            </ul>\n                        ");
                        }
                        if (dynamicConfig.kbCall) {
                            html += '<p style="margin-top: 10px; font-size: 0.75rem; color: #666;">'.concat(dynamicConfig.kbCall, "</p>");
                        }
                    } else if (authType === "CLOUD") {
                        html += '\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <p>'.concat(dynamicConfig.infoAnswer, "</p>\n                            </li>\n                        </ul>\n                    ");
                        if (dynamicConfig.kbCall) {
                            html += '<p style="margin-top: 10px; font-size: 0.75rem; color: #666;">'.concat(dynamicConfig.kbCall, "</p>");
                        }
                    }
                } else if (providerId === "kakaobank") {
                    if (authType === "PUSH") {
                        html += '\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">1</span>\n                                <p>'.concat(dynamicConfig.infoAnswer1, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">2</span>\n                                <p>').concat(dynamicConfig.infoAnswer2, "</p>\n                            </li>\n                        </ul>\n                    ");
                    }
                } else if (providerId === "ibk") {
                    if (authType === "PUSH") {
                        html += '\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">1</span>\n                                <p>'.concat(dynamicConfig.infoAnswer1, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">2</span>\n                                <p>').concat(dynamicConfig.infoAnswer2, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">3</span>\n                                <p>').concat(dynamicConfig.infoAnswer3, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">4</span>\n                                <p>').concat(dynamicConfig.infoAnswer4, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">5</span>\n                                <p>').concat(dynamicConfig.infoAnswer5, "</p>\n                            </li>\n                        </ul>\n                    ");
                    } else if (authType === "CLOUD") {
                        var cloudUrl = (result === null || result === void 0 ? void 0 : result.cloudUrl) || "";
                        html += '\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">1</span>\n                                <p>'.concat(dynamicConfig.infoAnswer1, '</p>\n                            </li>\n                        </ul>\n                        <div style="text-align: center; margin: 15px 0;">\n                            <button type="button" class="ibk-cloud-confirm-btn" \n                            data-cloud-url="').concat(cloudUrl, '"\n                            style="padding: 8px 20px; background: #0066cc; color: white; border: none; border-radius: 4px; cursor: pointer;">확인</button>\n                        </div>\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">2</span>\n                                <p>').concat(dynamicConfig.infoAnswer2, "</p>\n                            </li>\n                        </ul>\n                    ");
                    }
                }
                return html;
            };
            if (dynamicConfig && authType === "CLOUD") {
                setInnerHTML("#EzauthContainer .body .step2 .step-info div:nth-child(1)", dynamicConfig.stepInfo1);
                setInnerHTML("#EzauthContainer .body .step2 .step-info div:nth-child(2)", dynamicConfig.stepInfo2);
                setInnerHTML("#EzauthContainer .body .step2 .step-info div:nth-child(3)", dynamicConfig.stepInfo3);
            } else {
                var _config$step1, _config$step10, _config$step11;
                setInnerHTML("#EzauthContainer .body .step2 .step-info div:nth-child(1)", (_config$step1 = config.step2) === null || _config$step1 === void 0 ? void 0 : _config$step1.stepInfo1);
                setInnerHTML("#EzauthContainer .body .step2 .step-info div:nth-child(2)", (_config$step10 = config.step2) === null || _config$step10 === void 0 ? void 0 : _config$step10.stepInfo2);
                setInnerHTML("#EzauthContainer .body .step2 .step-info div:nth-child(3)", (_config$step11 = config.step2) === null || _config$step11 === void 0 ? void 0 : _config$step11.stepInfo3);
            }
            var infoDetailElement = document.querySelector("#EzauthContainer .body .step2 .bottom .infomation .info-detail");
            if (infoDetailElement) {
                infoDetailElement.innerHTML = generateInfoDetailHTML();
            }
            setTextContent("#EzauthContainer .body .step2 .buttons .close", (_config$step12 = config.step2) === null || _config$step12 === void 0 ? void 0 : _config$step12.closeButton);
            setTextContent("#EzauthContainer .body .step2 .buttons .complete-auth .label", (_config$step13 = config.step2) === null || _config$step13 === void 0 ? void 0 : _config$step13.completeAuthButton);
        };
        child.makeEzauthUiErrorPage = function() {
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig.ui.errorPage : {};
            var setInnerHTML = function setInnerHTML(selector, html) {
                var el = document.querySelector(selector);
                if (el) el.innerHTML = html || "";
            };
            var setAttribute = function setAttribute(selector, attr, value) {
                if (selector) selector.setAttribute(attr, value || "");
            };
            setInnerHTML("#EzauthContainer .body .main .error-image .text", config.errorTitle);
            setInnerHTML("#EzauthContainer .body .main .comments .text-detail", config.errorDetail);
            setAttribute("#EzauthContainer .body .buttons .close", "title", config.closeButton);
            setAttribute("#EzauthContainer .body .buttons .close", "alt", config.closeButton);
            setAttribute("#EzauthContainer .body .buttons .previous", "title", config.previousButton);
            setAttribute("#EzauthContainer .body .buttons .previous", "alt", config.previousButton);
        };
        child.generateQrCode = function(_ref) {
            var qrScheme = _ref.qrScheme, qrImage = _ref.qrImage;
            var container = document.getElementById("qrCodeDisplay");
            if (!container) {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("QR Code display element not found.");
                return;
            }
            var ctx = container.getContext("2d");
            if (container) {
                ctx.clearRect(0, 0, container.width, container.height);
            }
            if (qrImage === "IMAGE" && qrScheme) {
                ctx = container.getContext("2d");
                var img = new Image;
                img.onload = function() {
                    container.width = img.width;
                    container.height = img.height;
                    ctx.clearRect(0, 0, container.width, container.height);
                    ctx.drawImage(img, 0, 0);
                };
                img.src = "data:image/png;base64,".concat(qrScheme);
            } else if (qrImage === "URL" && qrScheme) {
                container.width = 200;
                container.height = 200;
                var _ctx = container.getContext("2d");
                _ctx.fillStyle = "#ffffff";
                _ctx.fillRect(0, 0, 200, 200);
                var tempCanvas = document.createElement("canvas");
                qrcode__WEBPACK_IMPORTED_MODULE_8__.toCanvas(tempCanvas, qrScheme, {
                    width: 155,
                    height: 155,
                    margin: 0
                }, function(error) {
                    if (!error) {
                        var offsetX = (200 - 155) / 2;
                        var offsetY = (200 - 155) / 2;
                        _ctx.drawImage(tempCanvas, offsetX, offsetY);
                    } else {
                        _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("QR 코드 생성 실패:", error);
                    }
                });
            } else {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("Invalid QR info:", {
                    qrScheme,
                    qrImage
                });
            }
        };
        child.addEventListener = function(callback) {
            if (window.addEventListener) {
                window.addEventListener("message", this.eventHandler, false);
            } else if (window.attachEvent) {
                window.attachEvent("onmessage", this.eventHandler);
            }
        };
        child.eventHandler = function(event) {
            var data = event.data;
            if (!data || (0, _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_1__["default"])(data) !== "object" || !data.sender) return;
            switch (data.sender) {
              case "MLHub":
                child.handleMLHubMessage(event, data);
                break;

              case "MLCloud":
                child.handleMLCloudMessage(event, data);
                break;

              default:
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("unidentified sender");
            }
        };
        child.handleMLHubMessage = function(event, data) {
            child.cleanEzauthUiProvider();
            child.showEzauthUIStep1();
            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined" && typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined") {
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].setParam(data);
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].getEzauthJsonConf(function(data) {
                    if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].getUiConf(function(data) {
                            child.makeEzauthUiWebAccessibility();
                            if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                                child.makeEzauthUiStep1(true);
                            } else {
                                child.makeEzauthUiStep1(false);
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr, null, null, null, "close", function() {
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(data, null);
                                });
                            }
                            child.getDecInputData();
                        });
                    } else {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(data, null);
                    }
                });
            } else {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("Required external dependencies (EzauthCore, EzauthErrorHandler, EzauthAlert) are not defined.");
            }
        };
        child.handleMLCloudMessage = function(event, data) {
            var iframe = document.getElementById("authIframe");
            var container = document.getElementById("iframeContainer");
            if (iframe && event.source !== iframe.contentWindow) {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("Source is not iframe");
                return;
            }
            if (data.sender !== "MLCloud") {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("wrong sender:", data.sender);
                return;
            }
            if (typeof data.result === "undefined") {
                return;
            }
            switch (data.result) {
              case "success":
                if (iframe && event.source !== iframe.contentWindow) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("Source is not iframe");
                    return;
                }
                if (iframe) {
                    iframe.remove();
                }
                if (container) {
                    container.style.display = "none";
                }
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].userReqYn = "Y";
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthResult(function(authResultData) {
                    if (authResultData.errno !== _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH9991, authResultData);
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(authResultData.errstr);
                        return;
                    }
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthCheck(function(authCheckData) {
                        if (authCheckData.errno !== _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH9990, authCheckData);
                            _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(authCheckData.errstr);
                            return;
                        }
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK, authCheckData);
                    });
                });
                break;

              case "fail":
                if (iframe) {
                    iframe.remove();
                }
                if (container) {
                    container.style.display = "none";
                }
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_CLOUD_CANCEL, data);
                break;

              case "cancel":
                if (iframe) {
                    iframe.remove();
                }
                if (container) {
                    container.style.display = "none";
                }
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_CLOUD_CANCEL, data);
                break;

              case "change":
                if (iframe) {
                    iframe.remove();
                }
                if (container) {
                    container.style.display = "none";
                }
                child.callCloudAPI(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerCloudURL, function(success, error) {
                    if (!success) {
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(error);
                    }
                });
                break;
            }
        };
        child.getDecInputData = function() {
            var _EzauthCore$basicInfo3;
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig : {};
            var processData = function processData(resultData) {
                var innerDataKeys = Object.keys(resultData);
                if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) {
                    var _EzauthCore$ezauthJso5, _config$inputtypebiz3, _EzauthCore$jsonSelec3, _config$inputtype3, _EzauthCore$jsonSelec4;
                    var selectedInputtype = (_EzauthCore$ezauthJso5 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf) !== null && _EzauthCore$ezauthJso5 !== void 0 && (_EzauthCore$ezauthJso5 = _EzauthCore$ezauthJso5.service) !== null && _EzauthCore$ezauthJso5 !== void 0 && _EzauthCore$ezauthJso5.toLowerCase().includes("biz") ? (_config$inputtypebiz3 = config.inputtypebiz) === null || _config$inputtypebiz3 === void 0 ? void 0 : _config$inputtypebiz3[(_EzauthCore$jsonSelec3 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) === null || _EzauthCore$jsonSelec3 === void 0 ? void 0 : _EzauthCore$jsonSelec3.inputtypebiz] : (_config$inputtype3 = config.inputtype) === null || _config$inputtype3 === void 0 ? void 0 : _config$inputtype3[(_EzauthCore$jsonSelec4 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) === null || _EzauthCore$jsonSelec4 === void 0 ? void 0 : _EzauthCore$jsonSelec4.inputtype];
                    var elArray = [];
                    for (var i = 0; i < selectedInputtype.length; i++) {
                        var inputTypeName = selectedInputtype[i];
                        if (inputTypeName === "사업자등록번호") {
                            var el = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.biz-registration-number");
                            elArray.push([ "businessNumber", el ]);
                        } else if (inputTypeName === "이름") {
                            var _el3 = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.name");
                            elArray.push([ "name", _el3 ]);
                        } else if (inputTypeName === "생년월일") {
                            var _el4 = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.birth");
                            elArray.push([ "birthday", _el4 ]);
                        } else if (inputTypeName === "통신사") {
                            var hpLiElement = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp");
                            elArray.push([ "phone", hpLiElement ]);
                        } else if (inputTypeName === "전화번호") {
                            var _hpLiElement = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp");
                            elArray.push([ "phone", _hpLiElement ]);
                        } else if (inputTypeName === "주민번호 앞자리") {
                            var ssnLiElement = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.ssn");
                            var ssn1 = ssnLiElement ? ssnLiElement.querySelector(".ssn1") : null;
                            elArray.push([ "ssn1", ssn1 ]);
                        } else if (inputTypeName === "주민번호 뒷자리") {
                            var _ssnLiElement2 = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.ssn");
                            var ssn2 = _ssnLiElement2 ? _ssnLiElement2.querySelector(".ssn2") : null;
                            elArray.push([ "ssn2", ssn2 ]);
                        }
                    }
                    var _iterator3 = _createForOfIteratorHelper(elArray.entries()), _step3;
                    try {
                        for (_iterator3.s(); !(_step3 = _iterator3.n()).done; ) {
                            var _step3$value = (0, _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__["default"])(_step3.value, 2), index = _step3$value[0], _step3$value$ = (0, 
                            _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__["default"])(_step3$value[1], 2), key = _step3$value$[0], _el5 = _step3$value$[1];
                            if (!_el5) continue;
                            if (!innerDataKeys.includes(key)) continue;
                            if (key === "phone") {
                                var phone = resultData["phone"] || "";
                                var phonePrefix = phone.slice(0, 3);
                                var phoneSuffix = phone.slice(3);
                                var telPrefix = _el5.querySelector("select.sel_telnum");
                                var telNumber = _el5.querySelector("input[type='text']");
                                if (telPrefix) telPrefix.value = phonePrefix;
                                if (telNumber) telNumber.value = phoneSuffix;
                                continue;
                            }
                            var liEl = elArray[index][1];
                            liEl.querySelector("input").value = resultData[key];
                        }
                    } catch (err) {
                        _iterator3.e(err);
                    } finally {
                        _iterator3.f();
                    }
                    document.querySelectorAll(".body .step1 .policys .policy[data-hidden='false'] img.checkbox").forEach(function(el) {
                        el.classList.add("on");
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                            el.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_nor_1.svg");
                        }
                    });
                    if (child.isAllPolicyChecked()) {
                        var allAgreeCheckbox = document.querySelector(".body .step1 .policys .policy-title img.checkbox");
                        if (allAgreeCheckbox) {
                            allAgreeCheckbox.classList.add("on");
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                allAgreeCheckbox.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/ico_chk_all_nor_1.svg");
                            }
                        }
                    }
                }
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_INITIALIZED, null);
                var closeButtonImg = document.querySelector("#EzauthContainer > div > header > div.right > div > img");
                if (closeButtonImg) {
                    closeButtonImg.focus();
                }
            };
            if (!((_EzauthCore$basicInfo3 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) !== null && _EzauthCore$basicInfo3 !== void 0 && _EzauthCore$basicInfo3.userInfo)) {
                var raw = sessionStorage.getItem("EZAuth");
                if (raw == null) {
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_INITIALIZED, null);
                    var closeButtonImg = document.querySelector("#EzauthContainer > div > header > div.right > div > img");
                    if (closeButtonImg) {
                        closeButtonImg.focus();
                    }
                    return;
                }
                var parsed = null;
                try {
                    parsed = JSON.parse(raw);
                } catch (e) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_9__["default"].error("JSON parse error:", e);
                    parsed = null;
                }
                var cipherInput = parsed ? parsed.ciphertext || parsed.data || parsed.value || raw : raw;
                var body = {
                    data: cipherInput,
                    code: "decrypt"
                };
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].endecryptReq(body, function(decryptedObj) {
                    if (!decryptedObj || (0, _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_1__["default"])(decryptedObj) !== "object" || decryptedObj.resultCode !== "2000") return;
                    var resultData = JSON.parse(decryptedObj.data);
                    processData(resultData);
                });
            } else {
                processData(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.userInfo);
            }
        };
        child.showEzauthUIStep1 = function() {
            var step1 = document.querySelector("#EzauthContainer .body .step1");
            var step2 = document.querySelector("#EzauthContainer .body .step2");
            if (step1) step1.style.display = "flex";
            if (step2) step2.style.display = "none";
        };
        child.showEzauthUIStep2 = function() {
            var step1 = document.querySelector("#EzauthContainer .body .step1");
            var step2 = document.querySelector("#EzauthContainer .body .step2");
            if (step1) step1.style.display = "none";
            if (step2) step2.style.display = "flex";
        };
        child.isAllPolicyChecked = function() {
            var parentPolicys = document.querySelector("#EzauthContainer .body .step1 .policys");
            if (!parentPolicys) return false;
            var visiblePolicies = parentPolicys.querySelectorAll(".policy[data-hidden='false']");
            var checkedVisiblePolicies = parentPolicys.querySelectorAll(".policy[data-hidden='false'] img.checkbox.on");
            return visiblePolicies.length > 0 && checkedVisiblePolicies.length === visiblePolicies.length;
        };
        child.showQRTimerUI = function(qrTimer) {
            if (child.currentTimerId) {
                clearInterval(child.currentTimerId);
            }
            var timerDisplay = document.getElementById("qrTimerDisplay");
            var qrGuide = document.querySelector(".qr-guide");
            var startTime = Date.now();
            child.isTimerExpired = false;
            var durationMs = qrTimer * 1e3;
            var totalTimeMinutes = Math.floor(qrTimer / 60);
            var totalTimeSeconds = qrTimer % 60;
            var updateTimer = function updateTimer() {
                if (child.isTimerExpired) {
                    return;
                }
                var currentTime = Date.now();
                var elapsedTimeMs = currentTime - startTime;
                var timeLeftSec = Math.max(0, Math.ceil((durationMs - elapsedTimeMs) / 1e3));
                var minutes = Math.floor(timeLeftSec / 60);
                var seconds = timeLeftSec % 60;
                timerDisplay.textContent = "".concat(minutes, ":").concat(seconds.toString().padStart(2, "0"));
                if (qrGuide) {
                    qrGuide.textContent = "휴대폰 카메라로 QR코드를 촬영하세요";
                    qrGuide.style.color = "#222";
                }
                if (timeLeftSec <= 10) {
                    timerDisplay.style.color = "#ef4444";
                } else if (timeLeftSec <= 30) {
                    timerDisplay.style.color = "#f97316";
                } else {
                    timerDisplay.style.color = "#009dce";
                }
                if (timeLeftSec <= 0) {
                    clearInterval(child.currentTimerId);
                    timerDisplay.textContent = "".concat(totalTimeMinutes, ":").concat(totalTimeSeconds.toString().padStart(2, "0"));
                    timerDisplay.style.color = "#009dce";
                    child.currentTimerId = null;
                    child.isTimerExpired = true;
                    if (qrGuide) {
                        qrGuide.textContent = "QR코드 유효시간이 만료되었습니다.";
                        qrGuide.style.color = "#ef4444";
                    }
                    var qrRetryButton = document.querySelector("#EzauthContainer .body .step1 .user-info .qr-info .qr-code-section .qrRetryButton");
                    var qrRetryButtonDimmed = document.querySelector("#EzauthContainer .body .step1 .user-info .qr-info .qr-code-section .qr-dimmed");
                    if (qrRetryButton) {
                        qrRetryButton.style.display = "";
                        qrRetryButton.classList.add("on");
                        qrRetryButtonDimmed.classList.add("on");
                    }
                    var reqAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
                    if (reqAuthButtonStep1) reqAuthButtonStep1.style.display = "";
                    var completeAuthButtonStep1 = document.querySelector("#EzauthContainer .body .step1 .buttons .complete-auth");
                    if (completeAuthButtonStep1) completeAuthButtonStep1.style.display = "none";
                }
            };
            updateTimer();
            child.currentTimerId = setInterval(updateTimer, 1e3);
        };
        child.toggleQrLoading = function(visible) {
            var qrSection = document.querySelector(".body .step1 .user-info .qr-info");
            if (!qrSection) return;
            if (visible) {
                var canvas = qrSection.querySelector("#qrCodeDisplay");
                if (canvas) {
                    var ctx = canvas.getContext("2d");
                    ctx.clearRect(0, 0, canvas.width, canvas.height);
                }
                qrSection.querySelectorAll(".qr-timer, .qr-timer-text, .qr-guide").forEach(function(el) {
                    return el.style.display = "none";
                });
                qrSection.querySelector(".qr-loading").style.display = "flex";
            } else {
                qrSection.querySelectorAll(".qr-timer, .qr-timer-text, .qr-guide").forEach(function(el) {
                    return el.style.display = "";
                });
                qrSection.querySelector(".qr-loading").style.display = "none";
            }
        };
    })(EzauthChild);
    const __WEBPACK_DEFAULT_EXPORT__ = EzauthChild;
})();

window.EzAuthPAct = __webpack_exports__["default"];