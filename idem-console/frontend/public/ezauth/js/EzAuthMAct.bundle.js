"use strict";

var __webpack_modules__ = {
    "./src/ezauth/js/EzauthAlert.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
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
    "./src/ezauth/js/EzauthMobileModal.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
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
            function attachModalTabNavigationEvents(modalElement) {
                var confirmButton = modalElement.querySelector(".button.confirm");
                var contentSection = modalElement.querySelector("section.content");
                if (confirmButton) {
                    confirmButton.addEventListener("keydown", function(event) {
                        if (event.key === "Tab" && !event.shiftKey) {
                            event.preventDefault();
                            var focusableElements = contentSection.querySelectorAll('a[href], area[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), iframe, object, embed, [tabindex]:not([tabindex="-1"]), [contenteditable]');
                            if (focusableElements.length > 0) {
                                focusableElements[0].focus();
                            } else if (contentSection) {
                                if (!contentSection.hasAttribute("tabindex")) {
                                    contentSection.setAttribute("tabindex", "-1");
                                }
                                contentSection.focus();
                            }
                        }
                    });
                }
                if (contentSection) {
                    if (!contentSection.hasAttribute("tabindex")) {
                        contentSection.setAttribute("tabindex", "-1");
                    }
                    contentSection.addEventListener("keydown", function(event) {
                        if (event.key === "Tab" && event.shiftKey) {
                            event.preventDefault();
                            if (confirmButton) {
                                confirmButton.focus();
                            }
                        }
                    });
                }
            }
            modal.init = function() {
                if (modal.isInitialized === true) {
                    return;
                }
                if (!confirmButtonDelegateHandler) {
                    confirmButtonDelegateHandler = function confirmButtonDelegateHandler(event) {
                        if (event.target.closest("#EzauthModal .button.confirm")) {
                            if (event.type === "keyup" && (event.which === 13 || event.which === 32)) {
                                modal.close();
                            } else if (event.type === "click") {
                                modal.close();
                            }
                        }
                    };
                    document.addEventListener("click", confirmButtonDelegateHandler);
                    document.addEventListener("keyup", confirmButtonDelegateHandler);
                }
                modal.isInitialized = true;
            };
            modal.close = function() {
                var modalElement = document.getElementById("EzauthModal");
                if (modalElement) {
                    modalElement.remove();
                }
                modal.isInitialized = false;
                document.querySelector("#EzauthContainer").removeAttribute("inert");
                var originalTrigger = document.querySelector("#".concat(modal.policyId, " .button-show"));
                if (originalTrigger) {
                    originalTrigger.focus();
                }
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
                if (modal.titleOption.use) {
                    var titleSection = document.createElement("section");
                    titleSection.classList.add("title");
                    var titleDiv = document.createElement("div");
                    titleDiv.setAttribute("tabindex", "-1");
                    titleDiv.textContent = modal.titleOption.title;
                    titleSection.appendChild(titleDiv);
                    var modalWrap = modalDomObj.querySelector(".Modal-Wrap");
                    if (modalWrap) {
                        modalWrap.prepend(titleSection);
                    }
                }
                if (modal.closeButtonOption.use) {
                    var buttonsSection = document.createElement("section");
                    buttonsSection.classList.add("buttons");
                    var confirmButton = document.createElement("div");
                    confirmButton.setAttribute("tabindex", "0");
                    confirmButton.setAttribute("role", "button");
                    confirmButton.classList.add("button", "confirm");
                    confirmButton.textContent = modal.closeButtonOption.title;
                    buttonsSection.appendChild(confirmButton);
                    var _modalWrap = modalDomObj.querySelector(".Modal-Wrap");
                    if (_modalWrap) {
                        _modalWrap.appendChild(buttonsSection);
                    }
                }
                var contentSection = modalDomObj.querySelector(".content");
                var xhr = new XMLHttpRequest;
                xhr.open("GET", modal.path, true);
                xhr.onload = function() {
                    if (xhr.status >= 200 && xhr.status < 300) {
                        if (contentSection) {
                            contentSection.innerHTML = xhr.responseText;
                        }
                        attachModalTabNavigationEvents(modalDomObj);
                        var _confirmButton = modalDomObj.querySelector("button.btn_modal_close");
                        if (_confirmButton) {
                            _confirmButton.focus();
                        }
                        var agreeclosebtn = modalDomObj.querySelector("#EzauthModal .btn_modal_close");
                        if (agreeclosebtn) {
                            agreeclosebtn.addEventListener("click", function(event) {
                                event.preventDefault();
                                modal.close();
                            });
                            agreeclosebtn.addEventListener("keydown", function(event) {
                                if (event.which === 13) {
                                    event.preventDefault();
                                }
                            });
                            agreeclosebtn.addEventListener("keyup", function(event) {
                                event.preventDefault();
                                if (event.which === 13 || event.which === 32) {
                                    modal.close();
                                }
                            });
                        }
                    } else {
                        _utils_Logger_js__WEBPACK_IMPORTED_MODULE_0__["default"].error("Failed to load modal content: ", xhr.statusText);
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
    "./node_modules/@babel/runtime/helpers/esm/arrayLikeToArray.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
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
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _arrayWithHoles
        });
        function _arrayWithHoles(r) {
            if (Array.isArray(r)) return r;
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/defineProperty.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
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
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _nonIterableRest
        });
        function _nonIterableRest() {
            throw new TypeError("Invalid attempt to destructure non-iterable instance.\nIn order to be iterable, non-array objects must have a [Symbol.iterator]() method.");
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/slicedToArray.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
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
    __webpack_require__.r(__webpack_exports__);
    __webpack_require__.d(__webpack_exports__, {
        default: () => __WEBPACK_DEFAULT_EXPORT__
    });
    var _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/slicedToArray.js");
    var _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/typeof.js");
    var _EzauthCore__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__("./src/ezauth/js/EzauthCore.js");
    var _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__("./src/ezauth/js/EzauthUtils.js");
    var _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__("./src/ezauth/js/EzauthErrorHandler.js");
    var _EzauthMobileModal__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__("./src/ezauth/js/EzauthMobileModal.js");
    var _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__("./src/ezauth/js/EzauthAlert.js");
    var _EzauthBlock__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__("./src/ezauth/js/EzauthBlock.js");
    var _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__("./src/ezauth/utils/Logger.js");
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
    var EzauthMobileChild = {};
    (function(child) {
        function maxleng(obj, leng) {
            if (obj.value.length > leng) {
                obj.value = obj.value.substr(0, leng);
            }
        }
        window.maxleng = maxleng;
        var providerList = "";
        child.getElements = function() {
            return {
                closeButton: document.querySelector("#EzauthContainer > div > header > div.right > div > img"),
                nextButtonStep1: document.querySelector("#EzauthContainer > div > div > section.step1 > section.buttons > div.button.next"),
                providerDropdownBtn: document.querySelector("#biz_cert_btn"),
                authMethodsDropdownBtn: document.querySelector("#auth_methods_btn"),
                authMethodsText: document.querySelector("#auth_methods_btn span#auth_methods_selected_text"),
                completeButtonStep2: document.querySelector("#EzauthContainer > div > div > section.step2 > section > div"),
                cloudButton: document.querySelector("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.cloud-button"),
                appPushButton: document.querySelector("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.app-push-button"),
                app2appButton: document.querySelector("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.app2app-button")
            };
        };
        child.cleanupPolling = function() {
            if (child.currentAbortController) {
                child.currentAbortController.abort();
            }
        };
        child.closeDropdown = function(dropdownId) {
            var dropdownBtn = document.querySelector("#".concat(dropdownId));
            if (dropdownBtn) dropdownBtn.click();
        };
        child.resetAuthMethodButtons = function(activeButton) {
            document.querySelectorAll(".btn_auth_methods_menu_item").forEach(function(btn) {
                btn.classList.remove("on");
                btn.setAttribute("title", "선택되지않음");
            });
            if (activeButton) {
                activeButton.classList.add("on");
                activeButton.setAttribute("title", "선택됨");
            }
        };
        child.registEvent = function() {
            if (window.addEventListener) {
                window.addEventListener("message", child.eventHandler, false);
            } else if (window.attachEvent) {
                window.attachEvent("onmessage", child.eventHandler);
            }
            var elements = child.getElements();
            if (elements.nextButtonStep1) {
                elements.nextButtonStep1.addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (!event.shiftKey) {
                            event.preventDefault();
                            var els = child.getElements();
                            if (els.closeButton) {
                                els.closeButton.focus();
                            }
                        }
                    }
                });
            }
            if (elements.completeButtonStep2) {
                elements.completeButtonStep2.addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (!event.shiftKey) {
                            event.preventDefault();
                            var els = child.getElements();
                            if (els.closeButton) {
                                els.closeButton.focus();
                            }
                        }
                    }
                });
            }
            if (elements.closeButton) {
                elements.closeButton.addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (event.shiftKey) {
                            var els = child.getElements();
                            var isCompleteAuthButtonHidden = !els.completeButtonStep2 || els.completeButtonStep2.style.display === "none";
                            var isNextButtonStep1Hidden = !els.nextButtonStep1 || els.nextButtonStep1.style.display === "none";
                            if (isCompleteAuthButtonHidden) {
                                event.preventDefault();
                                if (els.nextButtonStep1) {
                                    els.nextButtonStep1.focus();
                                }
                            } else if (isNextButtonStep1Hidden) {
                                event.preventDefault();
                                if (els.completeButtonStep2) {
                                    els.completeButtonStep2.focus();
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
                if (!targetElement.closest(".dropdown_menu_wrap")) {
                    document.querySelectorAll(".dropdown_menu_wrap").forEach(function(wrapper) {
                        var menu = wrapper.querySelector(".dropdown_menu_list");
                        var btn = wrapper.querySelector(".dropdown_menu_btn");
                        if (menu) {
                            menu.classList.remove("on");
                            menu.setAttribute("aria-hidden", "true");
                            menu.querySelectorAll("[role='menuitem']").forEach(function(item) {
                                item.setAttribute("aria-hidden", "true");
                                item.setAttribute("tabindex", "-1");
                            });
                        }
                        if (btn) btn.setAttribute("aria-expanded", "false");
                    });
                }
                if (targetElement.closest("header .close img") || targetElement.closest(".body .step2 .button.close")) {
                    var parentPolicys = targetElement.closest(".policys");
                    if (parentPolicys) {
                        var encase = document.querySelector("#EzauthContainer .body .step1 .policys > .encase");
                        if (encase) {
                            encase.style.transition = "bottom 0.5s ease-in-out";
                            encase.style.bottom = "-100vh";
                            encase.addEventListener("transitionend", function handler() {
                                var nextButton = document.querySelector("#EzauthContainer .body .step1 .buttons .next");
                                var reqAuthButton = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
                                var policysContainer = document.querySelector("#EzauthContainer .body .step1 .policys");
                                var headerArea = document.querySelector("#EzauthContainer .encase .container-header");
                                if (headerArea) {
                                    headerArea.removeAttribute("inert");
                                }
                                var userInfoArea = document.querySelector("#EzauthContainer .encase .body .step1 .user-info");
                                if (userInfoArea) {
                                    userInfoArea.removeAttribute("inert");
                                }
                                var userManualArea = document.querySelector("#EzauthContainer .encase .body .step1 .user-manual");
                                if (userManualArea) {
                                    userManualArea.removeAttribute("inert");
                                }
                                if (nextButton) {
                                    nextButton.style.display = "flex";
                                    nextButton.focus();
                                }
                                if (reqAuthButton) reqAuthButton.style.display = "none";
                                if (policysContainer) policysContainer.style.display = "none";
                                encase.removeEventListener("transitionend", handler);
                            }, {
                                once: true
                            });
                        }
                    } else {
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined") {
                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_SIMPLEAUTH_CANCEL, null);
                        }
                    }
                } else if (targetElement.closest("#EzauthContainer .body .step1 #biz_cert_menu .dropdown_menu_list_inner .btn_biz_cert_menu_item")) {
                    var liElement = targetElement.closest("#EzauthContainer .body .step1 #biz_cert_menu .dropdown_menu_list_inner .btn_biz_cert_menu_item");
                    if (liElement) {
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined") {
                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = JSON.parse(liElement.dataset.provider);
                        }
                        if (!liElement.classList.contains("on")) {
                            document.querySelectorAll("#EzauthContainer .body .step1 #biz_cert_menu .dropdown_menu_list_inner .btn_biz_cert_menu_item").forEach(function(item) {
                                item.classList.remove("on");
                                item.setAttribute("title", "선택되지않음");
                            });
                            liElement.classList.add("on");
                            liElement.setAttribute("title", "선택됨");
                            switch (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerId) {
                              case "kakaobank":
                                var app2appButton = document.querySelector("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.app2app-button");
                                if (app2appButton) app2appButton.click();
                                break;

                              case "kb":
                              case "ibk":
                              case "standard":
                                var cloudButton = document.querySelector("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.cloud-button");
                                if (cloudButton) cloudButton.click();
                                break;
                            }
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined") {
                                child.makeEzauthUiAuthMethodInfo(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider);
                            }
                        }
                        child.closeDropdown("auth_methods_btn");
                    }
                } else if (targetElement.closest(".dropdown_menu_btn")) {
                    var _liElement = targetElement.closest(".dropdown_menu_btn");
                    if (_liElement.closest("#auth_methods_btn")) {
                        if (!_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) {
                            _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.certNotSelected, null, null, null, null, function() {
                                var _document$querySelect;
                                (_document$querySelect = document.querySelector("#auth_methods_btn")) === null || _document$querySelect === void 0 || _document$querySelect.focus();
                            });
                            return;
                        }
                    }
                    child.toggleDropdown(_liElement.closest(".dropdown_menu_wrap"));
                } else if (targetElement.closest("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.cloud-button")) {
                    child.closeDropdown("auth_methods_btn");
                    var clickedBtn = targetElement.closest(".cloud-button, .app-push-button, .app2app-button");
                    if (clickedBtn) {
                        child.resetAuthMethodButtons(clickedBtn);
                    }
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined") {
                        child.makeEzauthUiUserInfo(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider);
                    }
                    var signTypeSpan = document.querySelector("#auth_methods_btn span#auth_methods_selected_text");
                    if (signTypeSpan) signTypeSpan.textContent = "클라우드";
                    var _targetElement = document.querySelector("#EzauthContainer .body .step1 .user-info #auth_methods_btn");
                    _targetElement.focus();
                } else if (targetElement.closest("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.app-push-button")) {
                    child.closeDropdown("auth_methods_btn");
                    var _clickedBtn = targetElement.closest(".cloud-button, .app-push-button, .app2app-button");
                    if (_clickedBtn) {
                        child.resetAuthMethodButtons(_clickedBtn);
                    }
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined") {
                        child.makeEzauthUiUserInfo(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider);
                    }
                    var _signTypeSpan = document.querySelector("#auth_methods_btn span#auth_methods_selected_text");
                    if (_signTypeSpan) _signTypeSpan.textContent = "앱 알림(PUSH)";
                    var _targetElement2 = document.querySelector("#EzauthContainer .body .step1 .user-info #auth_methods_btn");
                    _targetElement2.focus();
                } else if (targetElement.closest("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.app2app-button")) {
                    child.closeDropdown("auth_methods_btn");
                    var _clickedBtn2 = targetElement.closest(".cloud-button, .app-push-button, .app2app-button");
                    if (_clickedBtn2) {
                        child.resetAuthMethodButtons(_clickedBtn2);
                    }
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined") {
                        child.makeEzauthUiUserInfo(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider);
                    }
                    var _signTypeSpan2 = document.querySelector("#auth_methods_btn span#auth_methods_selected_text");
                    if (_signTypeSpan2) _signTypeSpan2.textContent = "앱 실행(앱2앱)";
                    var _targetElement3 = document.querySelector("#EzauthContainer .body .step1 .user-info #auth_methods_btn");
                    _targetElement3.focus();
                } else if (targetElement.closest(".body .step1 .buttons .button.next")) {
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined") {
                        if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.maintenanceYN === "Y") {
                            _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.maintenanceNotice, null, null, null, null, function() {
                                var _document$querySelect2;
                                (_document$querySelect2 = document.querySelector(".body .step1 .user-info header .cert")) === null || _document$querySelect2 === void 0 || _document$querySelect2.click();
                            });
                            return;
                        }
                        if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].isValidUserInfo() === false) {
                            return;
                        }
                    }
                    var activeBtn = document.querySelector(".app2app-button.on, .cloud-button.on");
                    if (activeBtn) {
                        var _document$querySelect3;
                        (_document$querySelect3 = document.querySelector("#EzauthContainer .body .step1 .buttons .button.req-auth")) === null || _document$querySelect3 === void 0 || _document$querySelect3.click();
                        return;
                    }
                    var nextButton = document.querySelector("#EzauthContainer .body .step1 .buttons .next");
                    var reqAuthButton = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
                    if (nextButton) nextButton.style.display = "none";
                    if (reqAuthButton) reqAuthButton.style.display = "flex";
                    var policysContainer = document.querySelector("#EzauthContainer .body .step1 .policys");
                    if (policysContainer) policysContainer.style.display = "flex";
                    var _encase = document.querySelector("#EzauthContainer .body .step1 .policys > .encase");
                    if (_encase) {
                        _encase.style.bottom = "-100vh";
                        setTimeout(function() {
                            _encase.style.transition = "bottom 0.03s linear";
                            _encase.style.bottom = "0";
                        }, 300);
                    }
                    var headerArea = document.querySelector("#EzauthContainer .encase .container-header");
                    if (headerArea) {
                        headerArea.setAttribute("inert", "true");
                    }
                    var userInfoArea = document.querySelector("#EzauthContainer .encase .body .step1 .user-info");
                    if (userInfoArea) {
                        userInfoArea.setAttribute("inert", "true");
                    }
                    var userManualArea = document.querySelector("#EzauthContainer .encase .body .step1 .user-manual");
                    if (userManualArea) {
                        userManualArea.setAttribute("inert", "true");
                    }
                    var policysCloseButton = document.querySelector("#EzauthContainer > div > div > section.step1 > section.policys > div > header > div.right > div > img");
                    if (policysCloseButton) policysCloseButton.focus();
                } else if (targetElement.closest(".body .step1 .buttons .button.req-auth")) {
                    var ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
                    var _headerArea = document.querySelector("#EzauthContainer .encase .container-header");
                    if (_headerArea) {
                        _headerArea.removeAttribute("inert");
                    }
                    var _userInfoArea = document.querySelector("#EzauthContainer .encase .body .step1 .user-info");
                    if (_userInfoArea) {
                        _userInfoArea.removeAttribute("inert");
                    }
                    var _userManualArea = document.querySelector("#EzauthContainer .encase .body .step1 .user-manual");
                    if (_userManualArea) {
                        _userManualArea.removeAttribute("inert");
                    }
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined" && typeof window.parent.EzauthConfig !== "undefined") {
                        if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].isValidPolicys() === false) {
                            _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.notAgreeTerms, null, null, null, {
                                deviceType: "mobile",
                                state: "selectPolicy"
                            }, null);
                            return;
                        }
                        var authMethod = document.querySelector("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.on");
                        if (authMethod.classList.contains("cloud-button")) {
                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType = "CLOUD";
                        } else if (authMethod.classList.contains("app-push-button")) {
                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType = "PUSH";
                        } else if (authMethod.classList.contains("app2app-button")) {
                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType = "A2A";
                        }
                        _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].setLocalStorageDataAsJSON("lastAuthSelection", "providerId", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerId);
                        _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].setLocalStorageDataAsJSON("lastAuthSelection", "authType", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType);
                        child.cleanupPolling();
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthRequest(function(data) {
                            var _EzauthErrorHandler$e;
                            var requestType = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType;
                            if (data.errno === ((_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === null || _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] === void 0 || (_EzauthErrorHandler$e = _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error) === null || _EzauthErrorHandler$e === void 0 || (_EzauthErrorHandler$e = _EzauthErrorHandler$e.API_OK) === null || _EzauthErrorHandler$e === void 0 ? void 0 : _EzauthErrorHandler$e.errno) || 0)) {
                                child.makeEzauthUiStep2(data.result);
                                child.showEzauthUIStep2();
                                var handleVisibilityChange = function handleVisibilityChange() {
                                    if (document.visibilityState === "visible") {
                                        child.cleanupPolling();
                                        child.currentAbortController = new AbortController;
                                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].startPollingAuthStatus(data.result.txId, ezauthCore.ezauthJsonConf.pollingInterval, ezauthCore.ezauthJsonConf.pollingCnt, child.currentAbortController.signal);
                                    } else if (document.visibilityState === "hidden") {
                                        child.cleanupPolling();
                                    }
                                };
                                if (requestType === "CLOUD") {
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerCloudURL = data.result.cloudUrl;
                                    child.callCloudAPI(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerCloudURL, function(success, error) {
                                        if (!success) {
                                            _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(error);
                                        }
                                    });
                                } else if (requestType === "PUSH") {
                                    document.addEventListener("visibilitychange", handleVisibilityChange);
                                } else if (requestType === "A2A") {
                                    var uriScheme = data.result.uriScheme;
                                    document.addEventListener("visibilitychange", handleVisibilityChange);
                                    if (uriScheme) {
                                        var os = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.os;
                                        if (os == window.parent.EzauthConfig.MOBILE_OS.IOS) {
                                            var newWin = window.open(uriScheme);
                                            if (!newWin || newWin.closed || typeof newWin.closed == "undefined") {
                                                document.removeEventListener("visibilitychange", handleVisibilityChange);
                                                var closeElement = document.querySelector("#EzauthContainer .body .step2 .buttons .close");
                                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.popupBlocked, null, null, null, null, function() {
                                                    return closeElement.focus();
                                                });
                                            }
                                        } else if (os == window.parent.EzauthConfig.MOBILE_OS.ANDROID) {
                                            location.href = uriScheme;
                                        }
                                    } else {
                                        _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__["default"].warn("uriScheme is missing in response for MobileApp.");
                                    }
                                }
                                document.querySelector("#EzauthContainer .body .step2 .button.close").focus();
                            } else {
                                if (typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined") {
                                    _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr, null, null, null, null, function() {
                                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].getTxid(function(txidData) {
                                            if (txidData.errno !== 0) {
                                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(txidData.errstr);
                                            }
                                        });
                                    });
                                }
                                var policysEncase = document.querySelector("#EzauthContainer .body .step1 .policys > .encase");
                                if (policysEncase) {
                                    policysEncase.style.transition = "bottom 0.5s ease-in-out";
                                    policysEncase.style.bottom = "-100vh";
                                    policysEncase.addEventListener("transitionend", function handler() {
                                        var nextButton = document.querySelector("#EzauthContainer .body .step1 .buttons .next");
                                        var reqAuthButton = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
                                        var policysContainer = document.querySelector("#EzauthContainer .body .step1 .policys");
                                        if (nextButton) nextButton.style.display = "flex";
                                        if (reqAuthButton) reqAuthButton.style.display = "none";
                                        if (policysContainer) policysContainer.style.display = "none";
                                        policysEncase.removeEventListener("transitionend", handler);
                                    }, {
                                        once: true
                                    });
                                }
                            }
                        });
                    }
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
                            _checkboxImg.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/form_checkbox_nor.png");
                        }
                        document.querySelectorAll("#EzauthContainer .body .step1 .policys li:not(.policy-title) img.checkbox").forEach(function(el) {
                            el.classList.remove("on");
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                el.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/form_checkbox_nor.png");
                            }
                        });
                    } else {
                        _checkboxImg.classList.add("on");
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                            _checkboxImg.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/form_checkbox_chk.png");
                        }
                        document.querySelectorAll("#EzauthContainer .body .step1 .policys li:not(.policy-title) img.checkbox").forEach(function(el) {
                            el.classList.add("on");
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                el.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/form_checkbox_chk.png");
                            }
                        });
                    }
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].isValidPolicys()) {
                        var _reqAuthButton = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
                        if (_reqAuthButton) _reqAuthButton.classList.add("on");
                    } else {
                        var _reqAuthButton2 = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
                        if (_reqAuthButton2) _reqAuthButton2.classList.remove("on");
                    }
                } else if (targetElement.closest(".body .step1 .policys .policy .text")) {
                    var policyElement = targetElement.closest(".policy");
                    var _checkboxImg2 = policyElement ? policyElement.querySelector("img.checkbox") : null;
                    if (_checkboxImg2) {
                        _checkboxImg2.click();
                    }
                } else if (targetElement.closest(".body .step1 .policys li:not(.policy-title) img.checkbox")) {
                    var _checkboxImg3 = targetElement.closest(".body .step1 .policys li:not(.policy-title) img.checkbox");
                    if (!_checkboxImg3) return;
                    var _parentPolicys = _checkboxImg3.closest(".policys");
                    if (!_parentPolicys) return;
                    if (_checkboxImg3.classList.contains("on")) {
                        _checkboxImg3.classList.remove("on");
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                            _checkboxImg3.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/form_checkbox_nor.png");
                        }
                        var allAgreeCheckbox = _parentPolicys.querySelector(".policy-title img.checkbox");
                        if (allAgreeCheckbox) {
                            allAgreeCheckbox.classList.remove("on");
                            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                allAgreeCheckbox.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/form_checkbox_nor.png");
                            }
                        }
                    } else {
                        _checkboxImg3.classList.add("on");
                        if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                            _checkboxImg3.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/form_checkbox_chk.png");
                        }
                        var visiblePolicies = _parentPolicys.querySelectorAll(".policy[data-hidden='false']");
                        var checkedVisiblePolicies = _parentPolicys.querySelectorAll(".policy[data-hidden='false'] img.checkbox.on");
                        if (checkedVisiblePolicies.length === visiblePolicies.length) {
                            var _allAgreeCheckbox = _parentPolicys.querySelector(".policy-title img.checkbox");
                            if (_allAgreeCheckbox) {
                                _allAgreeCheckbox.classList.add("on");
                                if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) {
                                    _allAgreeCheckbox.setAttribute("src", _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.ezauthRootPath + "assets/img/form_checkbox_chk.png");
                                }
                            }
                        }
                    }
                    if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].isValidPolicys()) {
                        var _reqAuthButton3 = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
                        if (_reqAuthButton3) _reqAuthButton3.classList.add("on");
                    } else {
                        var _reqAuthButton4 = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
                        if (_reqAuthButton4) _reqAuthButton4.classList.remove("on");
                    }
                } else if (targetElement.closest(".body .step1 .policys li .button-show")) {
                    var _EzauthCore$ezauthJso;
                    var policyLi = targetElement.closest(".body .step1 .policys li");
                    var id = policyLi ? policyLi.getAttribute("id") : null;
                    if (id && typeof _EzauthMobileModal__WEBPACK_IMPORTED_MODULE_5__["default"] !== "undefined" && typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && (_EzauthCore$ezauthJso = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf) !== null && _EzauthCore$ezauthJso !== void 0 && (_EzauthCore$ezauthJso = _EzauthCore$ezauthJso.policys) !== null && _EzauthCore$ezauthJso !== void 0 && _EzauthCore$ezauthJso[id]) {
                        var _EzauthCore$basicInfo;
                        _EzauthMobileModal__WEBPACK_IMPORTED_MODULE_5__["default"].show(id, (((_EzauthCore$basicInfo = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) === null || _EzauthCore$basicInfo === void 0 ? void 0 : _EzauthCore$basicInfo.ezauthRootPath) || "") + _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf.policys[id].path, null, {
                            use: true,
                            title: "확 인"
                        });
                        document.querySelector("#EzauthContainer").setAttribute("inert", "true");
                    }
                } else if (targetElement.closest(".body .step2 .buttons .button.complete-auth")) {
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
                                            var phonePrefixEl = qs("#EzauthContainer .body .step1 .user-info li.hp input.telnum_start");
                                            var phoneSuffixEl = qs("#EzauthContainer .body .step1 .user-info li.hp input.telnum_end");
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
                                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                                    }
                                });
                            } else if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.MLH2990.errno) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                            } else {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr);
                            }
                        });
                    }
                } else if (targetElement.closest(".body .step2 .infomation .ibk-cloud-confirm-btn")) {
                    var _liElement2 = document.querySelector(".body .step2 .infomation .ibk-cloud-confirm-btn");
                    var cloudUrl = _liElement2.dataset.cloudUrl;
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
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(window.parent.EzauthConfig.ui.errorText.enterOnlyNum);
                            }
                        }
                    }
                });
            });
        };
        child.mobileFocusEvent = function() {
            var dropdownConfigs = [ {
                btnSelector: "#biz_cert_btn",
                menuSelector: "#biz_cert_menu",
                itemsSelector: "#biz_cert_menu .btn_biz_cert_menu_item"
            }, {
                btnSelector: "#auth_methods_btn",
                menuSelector: "#auth_methods_menu",
                itemsSelector: "#auth_methods_menu .btn_auth_methods_menu_item"
            } ];
            dropdownConfigs.forEach(function(config) {
                var dropdownBtn = document.querySelector(config.btnSelector);
                var menuItems = document.querySelectorAll(config.itemsSelector);
                if (!dropdownBtn || menuItems.length === 0) return;
                var firstMenuItem = menuItems[0];
                var lastMenuItem = menuItems[menuItems.length - 1];
                lastMenuItem.addEventListener("keydown", function(event) {
                    if (event.key === "Tab" && !event.shiftKey) {
                        event.preventDefault();
                        dropdownBtn.focus();
                    }
                });
                dropdownBtn.addEventListener("keydown", function(event) {
                    var menu = document.querySelector(config.menuSelector);
                    var isMenuOpen = menu && menu.classList.contains("on");
                    if (event.key === "Tab" && event.shiftKey && isMenuOpen) {
                        event.preventDefault();
                        lastMenuItem.focus();
                    }
                });
                firstMenuItem.addEventListener("keydown", function(event) {
                    if (event.key === "Tab" && event.shiftKey) {
                        event.preventDefault();
                        dropdownBtn.focus();
                    }
                });
            });
            var policyListElements = document.querySelectorAll("#EzauthContainer > div > div > section.step1 > section.policys > div > ul li");
            var policyList = Array.from(policyListElements);
            var reversePolicyList = [].concat(policyList).reverse();
            var lastPolicy = reversePolicyList[0];
            for (var i = 0; i < reversePolicyList.length - 1; i++) {
                if (reversePolicyList[i].style.display === "none" || reversePolicyList[i].style.visibility === "hidden") {
                    lastPolicy = reversePolicyList[i + 1];
                } else {
                    break;
                }
            }
            if (lastPolicy && lastPolicy.children[2]) {
                lastPolicy.children[2].addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (!event.shiftKey) {
                            event.preventDefault();
                            var reqAuthButton = document.querySelector("#EzauthContainer > div > div > section.step1 > section.buttons > div.button.req-auth");
                            if (reqAuthButton) {
                                reqAuthButton.focus();
                            }
                        }
                    }
                });
            }
            var reqAuthButtonPolicys = document.querySelector("#EzauthContainer > div > div > section.step1 > section.buttons > div.button.req-auth");
            if (reqAuthButtonPolicys) {
                reqAuthButtonPolicys.addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (!event.shiftKey) {
                            event.preventDefault();
                            var policysCloseButton = document.querySelector("#EzauthContainer > div > div > section.step1 > section.policys > div > header > div.right > div > img");
                            if (policysCloseButton) {
                                policysCloseButton.focus();
                            }
                        }
                    }
                });
            }
            var policysCloseButtonHeader = document.querySelector("#EzauthContainer > div > div > section.step1 > section.policys > div > header > div.right > div > img");
            if (policysCloseButtonHeader) {
                policysCloseButtonHeader.addEventListener("keydown", function(event) {
                    if (event.key === "Tab") {
                        if (event.shiftKey) {
                            event.preventDefault();
                            var reqAuthButton = document.querySelector("#EzauthContainer > div > div > section.step1 > section.buttons > div.button.req-auth");
                            if (reqAuthButton) {
                                reqAuthButton.focus();
                            }
                        }
                    }
                });
            }
        };
        child.callCloudAPI = function(providerCloudURL, callback) {
            var iframeURL = providerCloudURL;
            var origin = _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].getOrigin(iframeURL);
            if (!origin) {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__["default"].error("Invalid Cloud URL:", iframeURL);
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
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__["default"].error("postMessage failed:", error);
                    if (callback) callback(false, error.message);
                }
            };
            iframe.onerror = function() {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__["default"].error("iframe load failed");
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
            setAttributes("#EzauthContainer .body .step1 .user-info .body .btn_sign_type", "selectAuthMethod", "selectAuthMethod");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.biz-registration-number input", "bizTitleBusinessRegistrationNumber", "bizTitleBusinessRegistrationNumber");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.name input", "bizTitleName", "bizTitleName");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.birth input", "bizTitleBirth", "bizTitleBirth");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.hp select.telco", "bizTitleHPCompanySelect", "bizTitleHPCompanySelect");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.hp input.telnum_end", "bizTitleHP", "bizTitleHP");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.ssn .ssn1", "bizTitleSsn1", "bizTitleSsn1");
            setAttributes("#EzauthContainer .body .step1 .user-info .body li.ssn .ssn2", "bizTitleSsn2", "bizTitleSsn2");
            setAttributes("#EzauthContainer .body .step1 .policys .policy-title .checkbox", "allAgree", "allAgree");
            setPolicyAttributes("policy1", (_ezauthJsonConf$polic = ezauthJsonConf.policys.policy1) === null || _ezauthJsonConf$polic === void 0 ? void 0 : _ezauthJsonConf$polic.title);
            setPolicyAttributes("policy2", (_ezauthJsonConf$polic2 = ezauthJsonConf.policys.policy2) === null || _ezauthJsonConf$polic2 === void 0 ? void 0 : _ezauthJsonConf$polic2.title);
            setPolicyAttributes("policy3", (_ezauthJsonConf$polic3 = ezauthJsonConf.policys.policy3) === null || _ezauthJsonConf$polic3 === void 0 ? void 0 : _ezauthJsonConf$polic3.title);
            setPolicyAttributes("policy4", (_ezauthJsonConf$polic4 = ezauthJsonConf.policys.policy4) === null || _ezauthJsonConf$polic4 === void 0 ? void 0 : _ezauthJsonConf$polic4.title);
            setAttributes("#EzauthContainer .body .step1 .policys .policy .button-show", "policyShowButton", "policyShowButton");
            setAttributes("#EzauthContainer .body .step1 .buttons .close", "closeButton", "closeButton");
            setAttributes("#EzauthContainer .body .step1 .buttons .req-auth", "agreeAllAndReqAuthButton", "agreeAllAndReqAuthButton");
            setAttributes("#EzauthContainer .body .step2 .step-image .step02img .StepImg", "step02ImgText", "step02ImgText");
            setAttributes("#EzauthContainer .body .step2 .step-image .step03img .StepImg", "step03ImgText", "step03ImgText");
            setAttributes("#EzauthContainer .body .step2 .step-image .arrow", "progressArrow", "progressArrow");
            setAttributes("#EzauthContainer .body .step2 .buttons .close", "closeButton", "closeButton");
            setAttributes("#EzauthContainer .body .step2 .buttons .complete-auth", "completeAuthButton", "completeAuthButton");
        };
        child.makeEzauthUiStep1 = function(successGetGuiConf) {
            var _ezauthCore$ezauthJso3, _ezauthCore$ezauthJso4, _ezauthCore$ezauthJso5;
            var ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig.ui : {};
            var customerLogoImg = document.querySelector("#EzauthContainer header .img_logo-gov24 > img");
            if (customerLogoImg) {
                var _ezauthCore$ezauthJso, _config$header, _config$header2;
                customerLogoImg.setAttribute("src", ((_ezauthCore$ezauthJso = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso === void 0 || (_ezauthCore$ezauthJso = _ezauthCore$ezauthJso.siteInfo) === null || _ezauthCore$ezauthJso === void 0 ? void 0 : _ezauthCore$ezauthJso.siteImgUrl) || "");
                customerLogoImg.setAttribute("alt", ((_config$header = config.header) === null || _config$header === void 0 ? void 0 : _config$header.logoText) || "");
                customerLogoImg.setAttribute("title", ((_config$header2 = config.header) === null || _config$header2 === void 0 ? void 0 : _config$header2.logoText) || "");
            }
            var headerTitle = document.querySelector("#EzauthContainer header .title");
            if (headerTitle) {
                var _ezauthCore$ezauthJso2;
                headerTitle.innerHTML = ((_ezauthCore$ezauthJso2 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso2 === void 0 || (_ezauthCore$ezauthJso2 = _ezauthCore$ezauthJso2.siteInfo) === null || _ezauthCore$ezauthJso2 === void 0 ? void 0 : _ezauthCore$ezauthJso2.title) || "";
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
            var guideElement = document.querySelector("#EzauthContainer .body .step1 .ezauth-info .guide");
            if (guideElement && (((_ezauthCore$ezauthJso3 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso3 === void 0 ? void 0 : _ezauthCore$ezauthJso3.guideShow) === undefined || ((_ezauthCore$ezauthJso4 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso4 === void 0 ? void 0 : _ezauthCore$ezauthJso4.guideShow) === null || !((_ezauthCore$ezauthJso5 = ezauthCore.ezauthJsonConf) !== null && _ezauthCore$ezauthJso5 !== void 0 && _ezauthCore$ezauthJso5.guideShow))) {
                guideElement.style.display = "none";
            }
            var nextButton = document.querySelector("#EzauthContainer .body .step1 .buttons .next");
            if (nextButton) {
                var _config$step3;
                nextButton.textContent = ((_config$step3 = config.step1) === null || _config$step3 === void 0 || (_config$step3 = _config$step3.sectionButtons) === null || _config$step3 === void 0 ? void 0 : _config$step3.nextButton) || "";
            }
            var reqAuthButton = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
            if (reqAuthButton) {
                var _config$step4;
                reqAuthButton.textContent = ((_config$step4 = config.step1) === null || _config$step4 === void 0 || (_config$step4 = _config$step4.sectionButtons) === null || _config$step4 === void 0 ? void 0 : _config$step4.agreeAllAndReqAuthButton) || "";
            }
            if (successGetGuiConf) {
                var _ezauthCore$jsonUiCon2, _ezauthCore$jsonUiCon3;
                var lastSelection = _EzauthUtils__WEBPACK_IMPORTED_MODULE_3__["default"].getLocalStorageDataAsJSON("lastAuthSelection");
                var lastProviderId = lastSelection === null || lastSelection === void 0 ? void 0 : lastSelection.providerId;
                var lastAuthType = lastSelection === null || lastSelection === void 0 ? void 0 : lastSelection.authType;
                if (lastAuthType === "QR") {
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = null;
                } else {
                    var _ezauthCore$jsonUiCon;
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = ((ezauthCore === null || ezauthCore === void 0 || (_ezauthCore$jsonUiCon = ezauthCore.jsonUiConf) === null || _ezauthCore$jsonUiCon === void 0 || (_ezauthCore$jsonUiCon = _ezauthCore$jsonUiCon.uiConf) === null || _ezauthCore$jsonUiCon === void 0 ? void 0 : _ezauthCore$jsonUiCon.providerList) || []).find(function(p) {
                        return p.providerId === lastProviderId;
                    }) || null;
                }
                var providerListArray = JSON.parse(JSON.stringify(((_ezauthCore$jsonUiCon2 = ezauthCore.jsonUiConf) === null || _ezauthCore$jsonUiCon2 === void 0 || (_ezauthCore$jsonUiCon2 = _ezauthCore$jsonUiCon2.uiConf) === null || _ezauthCore$jsonUiCon2 === void 0 ? void 0 : _ezauthCore$jsonUiCon2.providerList) || []));
                if (((_ezauthCore$jsonUiCon3 = ezauthCore.jsonUiConf) === null || _ezauthCore$jsonUiCon3 === void 0 || (_ezauthCore$jsonUiCon3 = _ezauthCore$jsonUiCon3.uiConf) === null || _ezauthCore$jsonUiCon3 === void 0 ? void 0 : _ezauthCore$jsonUiCon3.random) === true) {
                    providerListArray = providerListArray.sort(function() {
                        return Math.random() - .5;
                    });
                }
                var index = providerListArray.findIndex(function(p) {
                    return p.providerId === lastProviderId;
                });
                if (index > 0) {
                    var _providerListArray$sp = providerListArray.splice(index, 1), _providerListArray$sp2 = (0, 
                    _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__["default"])(_providerListArray$sp, 1), target = _providerListArray$sp2[0];
                    providerListArray.unshift(target);
                }
                providerListArray.forEach(function(provider) {
                    child.makeEzauthUiProvider(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider, provider);
                });
                if (_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) {
                    var selectedProviderId = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerId;
                    var isProviderRendered = document.querySelector('#EzauthContainer .body .step1 #biz_cert_menu .btn_biz_cert_menu_item[data-provider*="'.concat(selectedProviderId, '"]'));
                    if (!isProviderRendered) {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider = null;
                    }
                }
                var authConfig = {
                    CLOUD: {
                        selector: "#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.cloud-button",
                        label: "클라우드"
                    },
                    PUSH: {
                        selector: "#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.app-push-button",
                        label: "앱 알림(PUSH)"
                    },
                    A2A: {
                        selector: "#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.app2app-button",
                        label: "앱 실행(앱2앱)"
                    }
                };
                var selectedAuthConfig = authConfig[lastAuthType];
                if (selectedAuthConfig && _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider) {
                    var targetButton = document.querySelector(selectedAuthConfig.selector);
                    child.resetAuthMethodButtons(targetButton);
                    child.makeEzauthUiUserInfo(_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider);
                    var signTypeSpan = document.querySelector("#EzauthContainer .body .step1 .user-info .auth_methods span#auth_methods_selected_text");
                    if (signTypeSpan) signTypeSpan.textContent = selectedAuthConfig.label;
                } else {
                    child.makeEzauthUiUserInfo(null);
                }
            } else {
                child.makeEzauthUiUserInfo(null);
            }
            var policysHeaderTitle = document.querySelector("#EzauthContainer .body .step1 .policys header .title");
            if (policysHeaderTitle) {
                var _ezauthCore$ezauthJso6;
                policysHeaderTitle.innerHTML = ((_ezauthCore$ezauthJso6 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso6 === void 0 || (_ezauthCore$ezauthJso6 = _ezauthCore$ezauthJso6.policys) === null || _ezauthCore$ezauthJso6 === void 0 ? void 0 : _ezauthCore$ezauthJso6.policyTitle) || "";
            }
            var policysHeaderCloseImg = document.querySelector("#EzauthContainer .body .step1 .policys header .close img");
            if (policysHeaderCloseImg) {
                var _ezauthCore$basicInfo2;
                policysHeaderCloseImg.setAttribute("src", ((_ezauthCore$basicInfo2 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo2 === void 0 ? void 0 : _ezauthCore$basicInfo2.ezauthRootPath) + "assets/img/icon_close_blk.png" || 0);
            }
            var allAgreeText = document.querySelector("#EzauthContainer .body .step1 .policys .policy-title .all-agree");
            if (allAgreeText) {
                allAgreeText.textContent = window.parent.EzauthConfig.ui.step1.sectionPolicys.allAgree;
            }
            var buttonShows = document.querySelectorAll("#EzauthContainer .body .step1 .policys .body .button-show");
            buttonShows.forEach(function(btn) {
                btn.textContent = window.parent.EzauthConfig.ui.step1.sectionPolicys.policyShowButton;
            });
            var policyIds = [ "policy1", "policy2", "policy3", "policy4" ];
            policyIds.forEach(function(id) {
                var _ezauthCore$ezauthJso7;
                var policyConfig = (_ezauthCore$ezauthJso7 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso7 === void 0 || (_ezauthCore$ezauthJso7 = _ezauthCore$ezauthJso7.policys) === null || _ezauthCore$ezauthJso7 === void 0 ? void 0 : _ezauthCore$ezauthJso7[id];
                var policyElement = document.querySelector("#EzauthContainer .body .step1 .policys #".concat(id));
                if (policyElement && policyConfig) {
                    var isRequired = policyConfig.required === "Y";
                    var isHidden = policyConfig.hidden === true;
                    if (!isRequired && !isHidden) {
                        isHidden = true;
                    }
                    if (!isRequired || isHidden) {
                        policyElement.style.display = "none";
                    } else {
                        var textElement = policyElement.querySelector(".text");
                        if (textElement) textElement.textContent = policyConfig.title || "";
                    }
                    policyElement.setAttribute("data-required", policyConfig.required);
                    policyElement.setAttribute("data-hidden", isHidden);
                }
            });
        };
        child.makeEzauthUiProvider = function(lastSelectedProvider, provider) {
            var _ezauthCore$ezauthJso9, _ezauthCore$ezauthJso0, _ezauthCore$ezauthJso1;
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig : {};
            var ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
            if (ezauthCore.ezauthJsonConf.service === "authBiz") {
                sessionStorage.removeItem("EZAuth");
            }
            var serviceFound = false;
            if (provider.service) {
                for (var i = 0; i < provider.service.length; i++) {
                    var _ezauthCore$ezauthJso8;
                    if (((_ezauthCore$ezauthJso8 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso8 === void 0 ? void 0 : _ezauthCore$ezauthJso8.service) === provider.service[i]) {
                        serviceFound = true;
                        break;
                    }
                }
            }
            if (!serviceFound) {
                return;
            }
            if (((_ezauthCore$ezauthJso9 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso9 === void 0 || (_ezauthCore$ezauthJso9 = _ezauthCore$ezauthJso9.service) === null || _ezauthCore$ezauthJso9 === void 0 ? void 0 : _ezauthCore$ezauthJso9.toLowerCase().indexOf("biz")) < 0) {
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
            if (((_ezauthCore$ezauthJso0 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso0 === void 0 ? void 0 : _ezauthCore$ezauthJso0.service) === "sign" || ((_ezauthCore$ezauthJso1 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso1 === void 0 ? void 0 : _ezauthCore$ezauthJso1.service) === "signBiz") {
                var _ezauthCore$ezauthJso10, _ezauthCore$basicInfo3, _ezauthCore$basicInfo4, _ezauthCore$basicInfo5, _ezauthCore$ezauthJso11, _ezauthCore$ezauthJso12, _ezauthCore$ezauthJso13, _ezauthCore$basicInfo6, _ezauthCore$basicInfo7, _ezauthCore$basicInfo8, _ezauthCore$basicInfo9;
                var signType = (_ezauthCore$ezauthJso10 = ezauthCore.ezauthJsonConf.signInfo) === null || _ezauthCore$ezauthJso10 === void 0 ? void 0 : _ezauthCore$ezauthJso10.signType;
                if (((_ezauthCore$basicInfo3 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo3 === void 0 ? void 0 : _ezauthCore$basicInfo3.signType) !== undefined && ((_ezauthCore$basicInfo4 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo4 === void 0 ? void 0 : _ezauthCore$basicInfo4.signType) !== null && ((_ezauthCore$basicInfo5 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo5 === void 0 ? void 0 : _ezauthCore$basicInfo5.signType) !== "") {
                    signType = ezauthCore.basicInfo.signType;
                }
                var signTypeFound = false;
                if (provider.signType) {
                    for (var _i = 0; _i < provider.signType.length; _i++) {
                        if (signType === provider.signType[_i]) {
                            signTypeFound = true;
                            break;
                        }
                    }
                }
                if (!signTypeFound) {
                    return;
                }
                var signPKCSType = "PKCS7";
                if (((_ezauthCore$ezauthJso11 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso11 === void 0 ? void 0 : _ezauthCore$ezauthJso11.signPKCSType) !== undefined && ((_ezauthCore$ezauthJso12 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso12 === void 0 ? void 0 : _ezauthCore$ezauthJso12.signPKCSType) !== null && ((_ezauthCore$ezauthJso13 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso13 === void 0 ? void 0 : _ezauthCore$ezauthJso13.signPKCSType) !== "") {
                    signPKCSType = ezauthCore.ezauthJsonConf.signPKCSType;
                }
                if (((_ezauthCore$basicInfo6 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo6 === void 0 ? void 0 : _ezauthCore$basicInfo6.signPKCSType) !== undefined && ((_ezauthCore$basicInfo7 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo7 === void 0 ? void 0 : _ezauthCore$basicInfo7.signPKCSType) !== null && ((_ezauthCore$basicInfo8 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo8 === void 0 ? void 0 : _ezauthCore$basicInfo8.signPKCSType) !== "") {
                    signPKCSType = ezauthCore.basicInfo.signPKCSType;
                }
                var signPKCSTypeFound = false;
                if (provider.signPKCSType) {
                    for (var _i2 = 0; _i2 < provider.signPKCSType.length; _i2++) {
                        if (signPKCSType === provider.signPKCSType[_i2]) {
                            signPKCSTypeFound = true;
                            break;
                        }
                    }
                }
                if (!signPKCSTypeFound) {
                    return;
                }
                if (((_ezauthCore$basicInfo9 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo9 === void 0 || (_ezauthCore$basicInfo9 = _ezauthCore$basicInfo9.signContents) === null || _ezauthCore$basicInfo9 === void 0 ? void 0 : _ezauthCore$basicInfo9.length) > 1) {
                    if (provider.multi_sign !== "Y") {
                        return;
                    }
                }
            }
            var isMatched = (lastSelectedProvider === null || lastSelectedProvider === void 0 ? void 0 : lastSelectedProvider.providerId) === provider.providerId;
            var providerStr = '\n            <div class="btn_biz_cert_menu_item'.concat(isMatched ? " on" : "", '" \n                role="menuitem" \n                title="').concat(isMatched ? "선택됨" : "선택되지않음", '" \n                tabindex="0">\n                <div class="cert_logo" role="none" aria-hidden="true">\n                    <img src="" alt="').concat(provider.providerName, '" />\n                </div>\n                <span class="text">').concat(provider.providerName, "</span>\n                ").concat(isMatched ? '<span class="badge badge_recent">최근</span>' : "", "\n            </div>\n\t\t");
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
                    var _ezauthCore$basicInfo0;
                    imgElement.setAttribute("src", ((_ezauthCore$basicInfo0 = ezauthCore.basicInfo) === null || _ezauthCore$basicInfo0 === void 0 ? void 0 : _ezauthCore$basicInfo0.ezauthRootPath) + selectedProviderConfig.imgPath || "");
                }
            }
            providerObj.dataset.provider = JSON.stringify(provider);
            var providerListUl = document.querySelector("#EzauthContainer .body .step1 #biz_cert_menu .dropdown_menu_list_inner");
            if (providerListUl) {
                providerListUl.appendChild(providerObj);
            }
        };
        child.makeEzauthUiUserInfo = function(provider) {
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig : {};
            var ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
            var userInfoBody = document.querySelector("#EzauthContainer .body .step1 .user-info .body");
            if (userInfoBody) {
                userInfoBody.setAttribute("aria-live", "polite");
                userInfoBody.setAttribute("aria-relevant", "additions removals");
            }
            document.querySelectorAll("#EzauthContainer .body .step1 .user-info .body li:not(.dropdown_menu_wrap)").forEach(function(li) {
                li.style.display = "none";
                li.setAttribute("aria-hidden", "true");
                li.querySelectorAll("input, select, button").forEach(function(focusable) {
                    focusable.setAttribute("tabindex", "-1");
                });
            });
            if (provider !== null) {
                var _EzauthCore$basicInfo2, _ezauthCore$ezauthJso14;
                var providerObj = child.updateSelectedProviderUI();
                if (!providerObj) return;
                var cloudButton = document.querySelector("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.cloud-button");
                if (cloudButton) {
                    cloudButton.style.display = provider.providerUseCloud === "Y" ? "" : "none";
                }
                var buttons = [ document.querySelector(".app2app-button"), document.querySelector(".cloud-button") ];
                if (buttons.some(function(btn) {
                    return btn === null || btn === void 0 ? void 0 : btn.classList.contains("on");
                })) {
                    return;
                }
                if ((_EzauthCore$basicInfo2 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) !== null && _EzauthCore$basicInfo2 !== void 0 && _EzauthCore$basicInfo2.userInfo || sessionStorage.getItem("EZAuth") != null) {
                    child.getDecInputData();
                }
                var hpTelcoSelect = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp .telco");
                if (hpTelcoSelect) {
                    hpTelcoSelect.style.display = "none";
                    hpTelcoSelect.setAttribute("aria-hidden", "true");
                }
                var selectedInputtype;
                if (((_ezauthCore$ezauthJso14 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso14 === void 0 || (_ezauthCore$ezauthJso14 = _ezauthCore$ezauthJso14.service) === null || _ezauthCore$ezauthJso14 === void 0 ? void 0 : _ezauthCore$ezauthJso14.toLowerCase().indexOf("biz")) < 0) {
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
                        if (el) {
                            el.style.setProperty("display", "block", "important");
                            el.removeAttribute("aria-hidden");
                            var label = el.querySelector(".label");
                            var input = el.querySelector("input");
                            if (label && input && label.textContent) {
                                input.setAttribute("aria-label", label.textContent.trim());
                            }
                            el.querySelectorAll("input, select, button").forEach(function(focusable) {
                                focusable.setAttribute("tabindex", "0");
                            });
                            void el.offsetHeight;
                        }
                    } else if (inputTypeName === "이름") {
                        var _el = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.name");
                        if (_el) {
                            _el.style.setProperty("display", "block", "important");
                            _el.removeAttribute("aria-hidden");
                            var _label = _el.querySelector(".label");
                            var _input = _el.querySelector("input");
                            if (_label && _input && _label.textContent) {
                                _input.setAttribute("aria-label", _label.textContent.trim());
                            }
                            _el.querySelectorAll("input, select, button").forEach(function(focusable) {
                                focusable.setAttribute("tabindex", "0");
                            });
                            void _el.offsetHeight;
                        }
                    } else if (inputTypeName === "생년월일") {
                        var _el2 = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.birth");
                        if (_el2) {
                            _el2.style.setProperty("display", "block", "important");
                            _el2.removeAttribute("aria-hidden");
                            var _label2 = _el2.querySelector(".label");
                            var _input2 = _el2.querySelector("input");
                            if (_label2 && _input2 && _label2.textContent) {
                                _input2.setAttribute("aria-label", _label2.textContent.trim());
                            }
                            _el2.querySelectorAll("input, select, button").forEach(function(focusable) {
                                focusable.setAttribute("tabindex", "0");
                            });
                            void _el2.offsetHeight;
                        }
                    } else if (inputTypeName === "통신사") {
                        var hpTelco = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp .telco");
                        var hpInput = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp input");
                        if (hpTelco) {
                            hpTelco.style.flex = "11";
                            hpTelco.style.display = "block";
                        }
                        if (hpInput) {
                            hpInput.style.flex = "20";
                        }
                    } else if (inputTypeName === "전화번호") {
                        var hpLiElement = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp");
                        var _hpInput = hpLiElement ? hpLiElement.querySelector("input.telnum_end") : null;
                        var _hpTelco = hpLiElement ? hpLiElement.querySelector(".telco") : null;
                        if (_hpTelco && _hpTelco.style.display === "none") {
                            if (_hpInput) _hpInput.style.width = "100%";
                        } else {
                            if (_hpTelco) _hpTelco.style.flex = "11";
                            if (_hpInput) _hpInput.style.flex = "20";
                        }
                        if (hpLiElement) {
                            hpLiElement.style.setProperty("display", "block", "important");
                            hpLiElement.removeAttribute("aria-hidden");
                            var _label3 = hpLiElement.querySelector(".label");
                            if (_label3 && _label3.textContent) {
                                var labelText = _label3.textContent.trim();
                                if (_hpInput) _hpInput.setAttribute("aria-label", labelText);
                                var selTelnum = hpLiElement.querySelector(".sel_telnum");
                                if (selTelnum) selTelnum.setAttribute("aria-label", labelText + " 앞자리");
                                if (_hpTelco) _hpTelco.setAttribute("aria-label", "통신사");
                            }
                            hpLiElement.querySelectorAll("input, select, button").forEach(function(focusable) {
                                focusable.setAttribute("tabindex", "0");
                            });
                            void hpLiElement.offsetHeight;
                        }
                    } else if (inputTypeName === "주민번호 앞자리") {
                        var ssnLiElement = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.ssn");
                        var ssn1 = ssnLiElement ? ssnLiElement.querySelector(".ssn1") : null;
                        var ssn2 = ssnLiElement ? ssnLiElement.querySelector(".ssn2") : null;
                        if (ssn1 && ssn1.style.display === "none") {
                            if (ssn2) ssn2.style.width = "100%";
                        } else {
                            if (ssn1) ssn1.style.flex = "1";
                            if (ssn2) ssn2.style.flex = "1";
                        }
                        if (ssnLiElement) {
                            ssnLiElement.style.setProperty("display", "flex", "important");
                            ssnLiElement.removeAttribute("aria-hidden");
                            ssnLiElement.querySelectorAll("input, select, button").forEach(function(focusable) {
                                focusable.setAttribute("tabindex", "0");
                            });
                            void ssnLiElement.offsetHeight;
                        }
                    } else if (inputTypeName === "주민번호 뒷자리") {
                        var _ssnLiElement = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.ssn");
                        var _ssn = _ssnLiElement ? _ssnLiElement.querySelector(".ssn1") : null;
                        var _ssn2 = _ssnLiElement ? _ssnLiElement.querySelector(".ssn2") : null;
                        if (_ssn2 && _ssn2.style.display === "none") {
                            if (_ssn) _ssn.style.width = "100%";
                        } else {
                            if (_ssn) _ssn.style.flex = "1";
                            if (_ssn2) _ssn2.style.flex = "1";
                        }
                        if (_ssnLiElement) {
                            _ssnLiElement.style.setProperty("display", "flex", "important");
                            _ssnLiElement.removeAttribute("aria-hidden");
                            _ssnLiElement.querySelectorAll("input, select, button").forEach(function(focusable) {
                                focusable.setAttribute("tabindex", "0");
                            });
                            void _ssnLiElement.offsetHeight;
                        }
                    }
                }
            }
            setTimeout(function() {
                var userAgent = navigator.userAgent || navigator.vendor || window.opera;
                var isIOS = /iPad|iPhone|iPod/.test(userAgent) && !window.MSStream;
                var isAndroid = /android/i.test(userAgent);
                var firstVisibleLabel = null;
                document.querySelectorAll("#EzauthContainer .body .step1 .user-info .body li:not(.dropdown_menu_wrap)").forEach(function(li) {
                    if (li.style.display !== "none" && li.getAttribute("aria-hidden") !== "true") {
                        void li.offsetHeight;
                        if (!firstVisibleLabel) {
                            firstVisibleLabel = li.querySelector(".label");
                        }
                        li.querySelectorAll("input, select, button").forEach(function(focusable) {
                            void focusable.offsetHeight;
                        });
                    }
                });
                if (isIOS && firstVisibleLabel) {
                    firstVisibleLabel.setAttribute("tabindex", "-1");
                    firstVisibleLabel.focus();
                    setTimeout(function() {
                        firstVisibleLabel.blur();
                    }, 500);
                }
            }, 100);
        };
        child.makeEzauthInit = function() {
            var _config$ui, _config$ui2, _config$ui3, _config$ui4;
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig : {};
            var ezauthCore = typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" ? _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] : {};
            var telcoSelect = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp select");
            if (telcoSelect) {
                while (telcoSelect.firstChild) {
                    telcoSelect.removeChild(telcoSelect.firstChild);
                }
            }
            var certInfoLabel = document.querySelector("#EzauthContainer .body .step1 .user-info .biz_cert .label");
            if (certInfoLabel) certInfoLabel.textContent = ((_config$ui = config.ui) === null || _config$ui === void 0 || (_config$ui = _config$ui.step1) === null || _config$ui === void 0 || (_config$ui = _config$ui.sectionUserInfo) === null || _config$ui === void 0 ? void 0 : _config$ui.bizTitleServiceListText) || "";
            var authInfoLabel = document.querySelector("#EzauthContainer .body .step1 .user-info .auth_methods .label");
            if (authInfoLabel) authInfoLabel.textContent = ((_config$ui2 = config.ui) === null || _config$ui2 === void 0 || (_config$ui2 = _config$ui2.step1) === null || _config$ui2 === void 0 || (_config$ui2 = _config$ui2.sectionUserInfo) === null || _config$ui2 === void 0 ? void 0 : _config$ui2.bizTitleAuthListText) || "";
            var certPText = document.querySelector("#EzauthContainer .body .step1 .user-info .biz_cert span#biz_cert_selected_text");
            if (certPText) certPText.textContent = ((_config$ui3 = config.ui) === null || _config$ui3 === void 0 || (_config$ui3 = _config$ui3.step1) === null || _config$ui3 === void 0 || (_config$ui3 = _config$ui3.sectionUserInfo) === null || _config$ui3 === void 0 ? void 0 : _config$ui3.bizTitleText) || "";
            var authPText = document.querySelector("#EzauthContainer .body .step1 .user-info .auth_methods span#auth_methods_selected_text");
            if (authPText) authPText.textContent = ((_config$ui4 = config.ui) === null || _config$ui4 === void 0 || (_config$ui4 = _config$ui4.step1) === null || _config$ui4 === void 0 || (_config$ui4 = _config$ui4.sectionUserInfo) === null || _config$ui4 === void 0 ? void 0 : _config$ui4.bizTitleAuthMethodText) || "";
            var bizRegNumLi = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.biz-registration-number");
            if (bizRegNumLi) {
                var _ezauthCore$ezauthJso15;
                if (!(((_ezauthCore$ezauthJso15 = ezauthCore.ezauthJsonConf) === null || _ezauthCore$ezauthJso15 === void 0 || (_ezauthCore$ezauthJso15 = _ezauthCore$ezauthJso15.service) === null || _ezauthCore$ezauthJso15 === void 0 ? void 0 : _ezauthCore$ezauthJso15.toLowerCase().indexOf("biz")) < 0)) {
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
                var _label4 = nameLi.querySelector(".label");
                if (_label4) _label4.textContent = ((_config$ui7 = config.ui) === null || _config$ui7 === void 0 || (_config$ui7 = _config$ui7.step1) === null || _config$ui7 === void 0 || (_config$ui7 = _config$ui7.sectionUserInfo) === null || _config$ui7 === void 0 ? void 0 : _config$ui7.bizTitleName) || "";
                var _input3 = nameLi.querySelector("input");
                if (_input3) _input3.setAttribute("placeholder", ((_config$ui8 = config.ui) === null || _config$ui8 === void 0 || (_config$ui8 = _config$ui8.step1) === null || _config$ui8 === void 0 || (_config$ui8 = _config$ui8.sectionUserInfo) === null || _config$ui8 === void 0 ? void 0 : _config$ui8.bizTitleNamePlaceholder) || "");
            }
            var birthLi = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.birth");
            if (birthLi) {
                var _config$ui9, _config$ui0;
                var _label5 = birthLi.querySelector(".label");
                if (_label5) _label5.textContent = ((_config$ui9 = config.ui) === null || _config$ui9 === void 0 || (_config$ui9 = _config$ui9.step1) === null || _config$ui9 === void 0 || (_config$ui9 = _config$ui9.sectionUserInfo) === null || _config$ui9 === void 0 ? void 0 : _config$ui9.bizTitleBirth) || "";
                var _input4 = birthLi.querySelector("input");
                if (_input4) _input4.setAttribute("placeholder", ((_config$ui0 = config.ui) === null || _config$ui0 === void 0 || (_config$ui0 = _config$ui0.step1) === null || _config$ui0 === void 0 || (_config$ui0 = _config$ui0.sectionUserInfo) === null || _config$ui0 === void 0 ? void 0 : _config$ui0.bizTitleBirthPlaceholder) || "");
            }
            var hpLi = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.hp");
            if (hpLi) {
                var _config$ui1, _config$ui14;
                var _label6 = hpLi.querySelector(".label");
                if (_label6) _label6.textContent = ((_config$ui1 = config.ui) === null || _config$ui1 === void 0 || (_config$ui1 = _config$ui1.step1) === null || _config$ui1 === void 0 || (_config$ui1 = _config$ui1.sectionUserInfo) === null || _config$ui1 === void 0 ? void 0 : _config$ui1.bizTitleHP) || "";
                if (telcoSelect) {
                    var _config$ui10;
                    var createOption = function createOption(value, text) {
                        var option = document.createElement("option");
                        option.value = value;
                        option.textContent = text;
                        return option;
                    };
                    telcoSelect.appendChild(createOption("", ((_config$ui10 = config.ui) === null || _config$ui10 === void 0 || (_config$ui10 = _config$ui10.step1) === null || _config$ui10 === void 0 || (_config$ui10 = _config$ui10.sectionUserInfo) === null || _config$ui10 === void 0 ? void 0 : _config$ui10.bizTitleHPCompany) || ""));
                    telcoSelect.options[0].disabled = true;
                    telcoSelect.options[0].hidden = true;
                    telcoSelect.options[0].selected = true;
                    if (config.TELCO_TYPE) {
                        var _config$ui11, _config$ui12, _config$ui13;
                        telcoSelect.appendChild(createOption(config.TELCO_TYPE.SKT, ((_config$ui11 = config.ui) === null || _config$ui11 === void 0 || (_config$ui11 = _config$ui11.step1) === null || _config$ui11 === void 0 || (_config$ui11 = _config$ui11.sectionUserInfo) === null || _config$ui11 === void 0 ? void 0 : _config$ui11.bizTitleHPCompanyS) || ""));
                        telcoSelect.appendChild(createOption(config.TELCO_TYPE.KT, ((_config$ui12 = config.ui) === null || _config$ui12 === void 0 || (_config$ui12 = _config$ui12.step1) === null || _config$ui12 === void 0 || (_config$ui12 = _config$ui12.sectionUserInfo) === null || _config$ui12 === void 0 ? void 0 : _config$ui12.bizTitleHPCompanyK) || ""));
                        telcoSelect.appendChild(createOption(config.TELCO_TYPE.LGU, ((_config$ui13 = config.ui) === null || _config$ui13 === void 0 || (_config$ui13 = _config$ui13.step1) === null || _config$ui13 === void 0 || (_config$ui13 = _config$ui13.sectionUserInfo) === null || _config$ui13 === void 0 ? void 0 : _config$ui13.bizTitleHPCompanyL) || ""));
                    }
                }
                var hpInputInner = hpLi.querySelector("input.telnum_end");
                if (hpInputInner) hpInputInner.setAttribute("placeholder", ((_config$ui14 = config.ui) === null || _config$ui14 === void 0 || (_config$ui14 = _config$ui14.step1) === null || _config$ui14 === void 0 || (_config$ui14 = _config$ui14.sectionUserInfo) === null || _config$ui14 === void 0 ? void 0 : _config$ui14.bizTitleHPPlaceholder) || "");
            }
            var ssnLi = document.querySelector("#EzauthContainer .body .step1 .user-info .body li.ssn");
            if (ssnLi) {
                var _config$ui15;
                var _label7 = ssnLi.querySelector(".label");
                if (_label7) _label7.textContent = ((_config$ui15 = config.ui) === null || _config$ui15 === void 0 || (_config$ui15 = _config$ui15.step1) === null || _config$ui15 === void 0 || (_config$ui15 = _config$ui15.sectionUserInfo) === null || _config$ui15 === void 0 ? void 0 : _config$ui15.bizTitleSsn) || "";
                var ssn1 = ssnLi.querySelector(".ssn1");
                if (ssn1) {
                    var _config$ui16;
                    ssn1.setAttribute("placeholder", ((_config$ui16 = config.ui) === null || _config$ui16 === void 0 || (_config$ui16 = _config$ui16.step1) === null || _config$ui16 === void 0 || (_config$ui16 = _config$ui16.sectionUserInfo) === null || _config$ui16 === void 0 ? void 0 : _config$ui16.bizTitleSsn1Placeholder) || "");
                    ssn1.style.flex = "1";
                }
                var ssn2 = ssnLi.querySelector(".ssn2");
                if (ssn2) {
                    var _config$ui17;
                    ssn2.setAttribute("placeholder", ((_config$ui17 = config.ui) === null || _config$ui17 === void 0 || (_config$ui17 = _config$ui17.step1) === null || _config$ui17 === void 0 || (_config$ui17 = _config$ui17.sectionUserInfo) === null || _config$ui17 === void 0 ? void 0 : _config$ui17.bizTitleSsn2Placeholder) || "");
                    ssn2.style.flex = "1";
                }
            }
        };
        child.makeEzauthUiAuthMethodInfo = function(provider) {
            var _EzauthCore$basicInfo3;
            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].cleanUserInfo();
            if ((_EzauthCore$basicInfo3 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) !== null && _EzauthCore$basicInfo3 !== void 0 && _EzauthCore$basicInfo3.userInfo || sessionStorage.getItem("EZAuth") != null) {
                child.getDecInputData();
            }
            var providerObj = child.updateSelectedProviderUI();
            if (!providerObj) return;
            var cloudButton = document.querySelector("#EzauthContainer .body .step1 #auth_methods_menu .dropdown_menu_list_inner .btn_auth_methods_menu_item.cloud-button");
            if (cloudButton) {
                cloudButton.style.display = provider.providerUseCloud === "Y" ? "" : "none";
            }
        };
        child.updateSelectedProviderUI = function() {
            var providerObj = document.querySelector("#EzauthContainer .body .step1 #biz_cert_menu .dropdown_menu_list_inner .btn_biz_cert_menu_item.on");
            if (!providerObj) return null;
            var certLogoImg = document.querySelector("#EzauthContainer .body .step1 .user-info .biz_cert #biz_cert_btn .cert_logo img");
            var providerImg = providerObj.querySelector("img");
            if (certLogoImg && providerImg) {
                certLogoImg.setAttribute("src", providerImg.getAttribute("src") || "");
            }
            var certPText = document.querySelector("#EzauthContainer .body .step1 .user-info #biz_cert_btn span#biz_cert_selected_text");
            var providerP = providerObj.querySelector("span");
            if (certPText && providerP) {
                certPText.textContent = providerP.textContent || "";
            }
            return providerObj;
        };
        child.makeEzauthUiStep2 = function(result) {
            var _dynamicConfigs$provi, _EzauthCore$ezauthJso2;
            var providerId = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.providerId;
            var authType = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo.requestType;
            var dynamicConfigs = {
                kakaobank: {
                    PUSH: {
                        infoQuestion: "문제 발생 시 조치방법",
                        infoAnswer1: '카카오뱅크 사업자 인증서 이용에 문제가 있는 경우, 카카오뱅크 앱을 최신버전으로 업데이트 하시거나<br>\n                    <a href="https://www.kakaobank.com/Help/Guide/Faq#%EB%B3%B4%EC%95%88%EC%84%A4%EC%A0%95" style="color:black;" target="_blank"><strong>[고객센터 자주묻는질문]</strong></a> 에서 해결방법을 찾아보세요.',
                        infoAnswer2: '문제가 지속되면 <a href="https://www.kakaobank.com/Help/Consult/Online/Ask"  style="color:black;" target="_blank"><strong>[고객센터 1:1문의]</strong></a>를 통해 문의 주시거나, \n                    카카오뱅크 고객센터(1599-3333)로 연락해 주세요.'
                    },
                    A2A: {
                        infoQuestion: "문제 발생 시 조치방법",
                        infoAnswer1: '카카오뱅크 사업자 인증서 이용에 문제가 있는 경우, 카카오뱅크 앱을 최신버전으로 업데이트 하시거나<br>\n                    <a href="https://www.kakaobank.com/Help/Guide/Faq#%EB%B3%B4%EC%95%88%EC%84%A4%EC%A0%95" style="color:black;" target="_blank"><strong>[고객센터 자주묻는질문]</strong></a> 에서 해결방법을 찾아보세요.',
                        infoAnswer2: '문제가 지속되면 <a href="https://www.kakaobank.com/Help/Consult/Online/Ask"  style="color:black;" target="_blank"><strong>[고객센터 1:1문의]</strong></a>를 통해 문의 주시거나, \n                    카카오뱅크 고객센터(1599-3333)로 연락해 주세요.'
                    }
                },
                kb: {
                    PUSH: {
                        infoQuestion: "<img src='assets/img/ico_info_gry.svg' style='width:1rem; height:1rem; margin-bottom:-0.15rem' >  혹시 KB스타기업뱅킹 앱 PUSH 알림이 오지 않으시나요?",
                        infoAnswer1: "다시 KB스타기업뱅킹앱 PUSH 알림을 동의해주세요.",
                        infoAnswer2: '<a href="https://my.kbstar.com/BfAw9FQ" style="color:black;" target="_blank"><u>앱 PUAH 알림 다시 동의하기</u></a> >',
                        infoQuestion2: "<img src='assets/img/ico_info_gry.svg' style='width:1rem; height:1rem; margin-bottom:-0.15rem' >  그래도 KB스타기업뱅킹 앱 PUSH알림이 오지 않으시나요?",
                        infoAnswerkb1: "KB스타기업뱅킹 앱 '인증요청내역' 화면을 통해 인증 진행 가능합니다.",
                        infoAnswerkb2: '<a href="https://my.kbstar.com/t2UBrhx" style="color:black;" target="_blank"><u>KB스타기업뱅킹 앱 인증요청내역 화면 이동하기</u></a> >',
                        kbCall: "※ KB국민은행 고객센터: 1588-9999"
                    },
                    A2A: {
                        infoQuestion: "KB스타기업뱅킹 앱을 통해 인증을 진행합니다."
                    },
                    CLOUD: {
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
                    A2A: {
                        infoQuestion: "<strong>[i-ONE Bank(기업)앱 설치]</strong>가 휴대폰에서 되어 있는지 확인해주세요.",
                        infoAnswer1: "<strong>[i-ONE Bank(기업)앱 > 인증ᆞ보안 > IBK인증서 > 인증알림]</strong> 에서 인증요청 내용을 확인할 수 있습니다.",
                        infoAnswer2: "문제가 계속된다면, <strong>[IBK기업은행 고객센터 1588-2588, 1566-2566]</strong>로 문의 부탁드립니다."
                    },
                    CLOUD: {
                        infoAnswer1: "<strong>[IBK인증서 팝업창]</strong>이 열리지 않았다면 아래 <strong>'확인'버튼</strong>을 클릭해서 인증을 진행해 주세요.",
                        infoAnswer2: "문제가 계속된다면, 앱을 최신버전으로 업데이트 하시거나, <strong>[IBK기업은행 고객센터 1588-2588, 1566-2566]</strong>로 문의 부탁드립니다."
                    }
                }
            };
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig.ui.step2 : {};
            var dynamicConfig = (_dynamicConfigs$provi = dynamicConfigs[providerId]) === null || _dynamicConfigs$provi === void 0 ? void 0 : _dynamicConfigs$provi[authType];
            var setTextContent = function setTextContent(selector, text) {
                var el = document.querySelector(selector);
                if (el) el.textContent = text || "";
            };
            var setInnerHTML = function setInnerHTML(selector, html) {
                var el = document.querySelector(selector);
                if (el) el.innerHTML = html || "";
            };
            setInnerHTML("#EzauthContainer .body .step2 .text", config.titleMobileText);
            setInnerHTML("#EzauthContainer .body .step2 .text-detail", authType == "CLOUD" ? config.titleCloudTextDetail : config.titleMobileTextDetail);
            var step2Title = document.querySelector("#EzauthContainer .encase .middle .title");
            if (step2Title && (_EzauthCore$ezauthJso2 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf) !== null && _EzauthCore$ezauthJso2 !== void 0 && (_EzauthCore$ezauthJso2 = _EzauthCore$ezauthJso2.siteInfo) !== null && _EzauthCore$ezauthJso2 !== void 0 && _EzauthCore$ezauthJso2.title) {
                step2Title.textContent = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf.siteInfo.title;
            }
            var step01Img = document.querySelector("#EzauthContainer .body .step2 .middle .step01img img.StepImg");
            if (step01Img) {
                var defaultImg = "assets/img/mo_wait_nor.svg";
                step01Img.src = dynamicConfigs[providerId] ? "assets/img/mo_wait_".concat(providerId, ".png") : defaultImg;
            }
            var generateInfoDetailHTML = function generateInfoDetailHTML() {
                var html = "";
                if (!dynamicConfig) {
                    if (authType === "PUSH") {
                        html += '\n                        <div class="question">휴대폰에 인증요청 알림이 오지 않나요?</div>\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">1</span>\n                                <p>해당 앱 설치 및 로그인 여부를 확인하세요.</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">2</span>\n                                <p>앱 알림 수신동의가 되어있는지 확인해주세요.</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">3</span>\n                                <p>문제가 지속되면 도움말/ 이메일문의를 통해 문의해주세요.</p>\n                            </li>\n                        </ul>\n                    ';
                    } else if (authType === "A2A") {
                        html += '\n                        <div class="question">휴대폰에 설치된 앱을 통해 인증을 진행합니다.</div>\n                    ';
                    } else if (authType === "CLOUD") {
                        html += '\n                        <div class="question"><strong>클라우드 인증창이 뜨지 않나요?</strong></div>\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <p>입력하신 정보를 확인하신 후 다시 시도해주세요.</p>\n                            </li>\n                        </ul>\n                    ';
                    }
                    return html;
                }
                if (providerId === "kb") {
                    if (authType === "PUSH") {
                        html += '\n                        <div class="question">'.concat(dynamicConfig.infoQuestion, '</div>\n                        <ul class="question-info">\n                            <li class="indent">\n                                <p>').concat(dynamicConfig.infoAnswer1, '</p>\n                            </li>\n                            <li class="indent">\n                                <p style="margin-top: 0.5rem;">').concat(dynamicConfig.infoAnswer2, "</p>\n                            </li>\n                        </ul>\n                    ");
                        if (dynamicConfig.infoQuestion2) {
                            html += '\n                            <div class="question" style="margin-top: 20px;">'.concat(dynamicConfig.infoQuestion2, '</div>\n                            <ul class="question-info">\n                                <li class="indent">\n                                    <p>').concat(dynamicConfig.infoAnswerkb1, '</p>\n                                </li>\n                                <li class="indent">\n                                    <p style="margin-top: 0.5rem;">').concat(dynamicConfig.infoAnswerkb2, "</p>\n                                </li>\n                            </ul>\n                        ");
                        }
                        if (dynamicConfig.kbCall) {
                            html += '<p style="margin: 10px 0 0 17px; font-size: 0.75rem; color: #666;">'.concat(dynamicConfig.kbCall, "</p>");
                        }
                    } else if (authType === "A2A") {
                        html += '\n                        <div class="question" style="padding: 0px;">'.concat(dynamicConfig.infoQuestion, "</div>\n                    ");
                    } else if (authType === "CLOUD") {
                        html += '\n                        <div class="question">'.concat(dynamicConfig.infoQuestion, '</div>\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <p>').concat(dynamicConfig.infoAnswer, "</p>\n                            </li>\n                        </ul>\n                    ");
                        if (dynamicConfig.kbCall) {
                            html += '<p style="margin-top: 10px; font-size: 0.75rem; color: #666;">'.concat(dynamicConfig.kbCall, "</p>");
                        }
                    }
                } else if (providerId === "kakaobank" && (authType === "PUSH" || authType === "A2A")) {
                    html += '\n                    <div class="question">'.concat(dynamicConfig.infoQuestion, '</div>\n                    <ul class="question-info">\n                        <li class="num-text">\n                            <span class="num">1</span>\n                            <p>').concat(dynamicConfig.infoAnswer1, '</p>\n                        </li>\n                        <li class="num-text">\n                            <span class="num">2</span>\n                            <p>').concat(dynamicConfig.infoAnswer2, "</p>\n                        </li>\n                    </ul>\n                ");
                } else if (providerId === "ibk") {
                    if (authType === "PUSH") {
                        html += '\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">1</span>\n                                <p>'.concat(dynamicConfig.infoAnswer1, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">2</span>\n                                <p>').concat(dynamicConfig.infoAnswer2, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">3</span>\n                                <p>').concat(dynamicConfig.infoAnswer3, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">4</span>\n                                <p>').concat(dynamicConfig.infoAnswer4, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">5</span>\n                                <p>').concat(dynamicConfig.infoAnswer5, "</p>\n                            </li>\n                        </ul>\n                    ");
                    } else if (authType === "A2A") {
                        html += '\n                        <div class="question">'.concat(dynamicConfig.infoQuestion, '</div>\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">1</span>\n                                <p>').concat(dynamicConfig.infoAnswer1, '</p>\n                            </li>\n                            <li class="num-text">\n                                <span class="num">2</span>\n                                <p>').concat(dynamicConfig.infoAnswer2, "</p>\n                            </li>\n                        </ul>\n                    ");
                    } else if (authType === "CLOUD") {
                        var cloudUrl = (result === null || result === void 0 ? void 0 : result.cloudUrl) || "";
                        html += '\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">1</span>\n                                <p>'.concat(dynamicConfig.infoAnswer1, '</p>\n                            </li>\n                        </ul>\n                        <div style="text-align: center; margin: 15px 0;">\n                            <button type="button" class="ibk-cloud-confirm-btn" \n                            data-cloud-url="').concat(cloudUrl, '"\n                            style="padding: 8px 20px; background: #0066cc; color: white; border: none; border-radius: 4px; cursor: pointer;">확인</button>\n                        </div>\n                        <ul class="question-info">\n                            <li class="num-text">\n                                <span class="num">2</span>\n                                <p>').concat(dynamicConfig.infoAnswer2, "</p>\n                            </li>\n                        </ul>\n                    ");
                    }
                }
                return html;
            };
            var infoDetailElement = document.querySelector("#EzauthContainer .body .step2 .bottom .infomation .info-detail");
            if (infoDetailElement) {
                infoDetailElement.innerHTML = generateInfoDetailHTML();
            }
            setTextContent("#EzauthContainer .body .step2 .buttons .close", config.closeButton);
            setTextContent("#EzauthContainer .body .step2 .buttons .complete-auth .label", config.completeAuthButton);
        };
        child.showEzauthUIStep2 = function() {
            var step1 = document.querySelector("#EzauthContainer .body .step1");
            var step2 = document.querySelector("#EzauthContainer .body .step2");
            if (step1) step1.style.display = "none";
            if (step2) step2.style.display = "block";
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
                handleMLHubMessage(event, data);
                break;

              case "MLCloud":
                handleMLCloudMessage(event, data);
                break;

              default:
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__["default"].error("unidentified sender");
            }
        };
        function handleMLHubMessage(event, data) {
            if (typeof _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"] !== "undefined" && typeof _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"] !== "undefined" && typeof _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"] !== "undefined") {
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].setParam(data);
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].getEzauthJsonConf(function(data) {
                    if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].getUiConf(function(data) {
                            if (data.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                                child.makeEzauthUiWebAccessibility();
                                child.makeEzauthUiStep1(true);
                            } else {
                                child.makeEzauthUiStep1(false);
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(data.errstr, null, null, null, "close", function() {
                                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(data, null);
                                });
                            }
                            child.showEzauthUIStep1();
                            _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_INITIALIZED, null);
                            setTimeout(function() {
                                var targetElement = document.querySelector("#EzauthContainer .body .step1 .user-info .biz_cert #biz_cert_btn");
                                if (targetElement) {
                                    targetElement.focus();
                                }
                            }, 10);
                        });
                    } else {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(data, null);
                    }
                });
            } else {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__["default"].error("Required external dependencies (EzauthCore, EzauthErrorHandler, EzauthAlert) are not defined.");
            }
        }
        function handleMLCloudMessage(event, data) {
            var iframe = document.getElementById("authIframe");
            var container = document.getElementById("iframeContainer");
            if (iframe && event.source !== iframe.contentWindow) {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__["default"].error("Source is not iframe");
                return;
            }
            if (data.sender !== "MLCloud") {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__["default"].error("wrong sender:", data.sender);
                return;
            }
            if (typeof data.result === "undefined") {
                return;
            }
            switch (data.result) {
              case "success":
                if (typeof _EzauthBlock__WEBPACK_IMPORTED_MODULE_7__["default"] !== "undefined") _EzauthBlock__WEBPACK_IMPORTED_MODULE_7__["default"].show();
                if (iframe) {
                    iframe.remove();
                }
                if (container) {
                    container.style.display = "none";
                }
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].userReqYn = "Y";
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthResult(function(authResultData) {
                    if (authResultData.errno === _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                        _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendAuthCheck(function(authCheckData) {
                            if (authCheckData.errno !== _EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK.errno) {
                                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(authCheckData.errstr);
                                return;
                            } else {
                                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_OK, authCheckData);
                            }
                        });
                    } else {
                        _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(authResultData.errstr);
                        return;
                    }
                });
                break;

              case "fail":
                if (iframe) {
                    iframe.remove();
                }
                if (container) {
                    container.style.display = "none";
                }
                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_CLOUD_FAIL.errstr);
                break;

              case "cancel":
                if (iframe) {
                    iframe.remove();
                }
                if (container) {
                    container.style.display = "none";
                }
                _EzauthAlert__WEBPACK_IMPORTED_MODULE_6__["default"].show(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_CLOUD_CANCEL.errstr);
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
        }
        child.getDecInputData = function() {
            var _EzauthCore$basicInfo4;
            var config = typeof window.parent.EzauthConfig !== "undefined" ? window.parent.EzauthConfig : {};
            var processData = function processData(resultData) {
                var _EzauthCore$ezauthJso3, _config$inputtypebiz3, _config$inputtype3;
                var innerDataKeys = Object.keys(resultData);
                var selectedInputtype = (_EzauthCore$ezauthJso3 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].ezauthJsonConf) !== null && _EzauthCore$ezauthJso3 !== void 0 && (_EzauthCore$ezauthJso3 = _EzauthCore$ezauthJso3.service) !== null && _EzauthCore$ezauthJso3 !== void 0 && _EzauthCore$ezauthJso3.toLowerCase().includes("biz") ? (_config$inputtypebiz3 = config.inputtypebiz) === null || _config$inputtypebiz3 === void 0 ? void 0 : _config$inputtypebiz3[_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.inputtypebiz] : (_config$inputtype3 = config.inputtype) === null || _config$inputtype3 === void 0 ? void 0 : _config$inputtype3[_EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].jsonSelectedProvider.inputtype];
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
                var _iterator = _createForOfIteratorHelper(elArray.entries()), _step;
                try {
                    for (_iterator.s(); !(_step = _iterator.n()).done; ) {
                        var _step$value = (0, _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__["default"])(_step.value, 2), index = _step$value[0], _step$value$ = (0, 
                        _babel_runtime_helpers_slicedToArray__WEBPACK_IMPORTED_MODULE_0__["default"])(_step$value[1], 2), key = _step$value$[0], _el5 = _step$value$[1];
                        if (!_el5) continue;
                        if (!innerDataKeys.includes(key)) continue;
                        if (key === "phone") {
                            var phone = resultData["phone"] || "";
                            var phonePrefix = phone.slice(0, 3);
                            var phoneSuffix = phone.slice(3);
                            var telPrefix = _el5.querySelector("input.sel_telnum");
                            var telNumber = _el5.querySelector("input.telnum_end");
                            if (telPrefix) telPrefix.value = phonePrefix;
                            if (telNumber) telNumber.value = phoneSuffix;
                            continue;
                        }
                        var liEl = elArray[index][1];
                        liEl.querySelector("input").value = resultData[key];
                    }
                } catch (err) {
                    _iterator.e(err);
                } finally {
                    _iterator.f();
                }
                _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_INITIALIZED, null);
                var els = child.getElements();
                if (els.closeButton) {
                    els.closeButton.focus();
                }
            };
            if (!((_EzauthCore$basicInfo4 = _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].basicInfo) !== null && _EzauthCore$basicInfo4 !== void 0 && _EzauthCore$basicInfo4.userInfo)) {
                var raw = sessionStorage.getItem("EZAuth");
                if (raw == null) {
                    _EzauthCore__WEBPACK_IMPORTED_MODULE_2__["default"].sendMessageToParent(_EzauthErrorHandler__WEBPACK_IMPORTED_MODULE_4__["default"].error.API_INITIALIZED, null);
                    var els = child.getElements();
                    if (els.closeButton) {
                        els.closeButton.focus();
                    }
                    return;
                }
                var parsed = null;
                try {
                    parsed = JSON.parse(raw);
                } catch (e) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_8__["default"].error("JSON parse error:", e);
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
            var nextButton = document.querySelector("#EzauthContainer .body .step1 .buttons .next");
            var reqAuthButton = document.querySelector("#EzauthContainer .body .step1 .buttons .req-auth");
            var policysContainer = document.querySelector("#EzauthContainer .body .step1 .policys");
            if (nextButton) nextButton.style.display = "flex";
            if (reqAuthButton) reqAuthButton.style.display = "none";
            if (policysContainer) policysContainer.style.display = "none";
            document.querySelectorAll(".dropdown_menu_list").forEach(function(menu) {
                menu.classList.remove("on");
                menu.setAttribute("aria-hidden", "true");
                menu.querySelectorAll("[role='menuitem']").forEach(function(item) {
                    item.setAttribute("aria-hidden", "true");
                    item.setAttribute("tabindex", "-1");
                });
            });
            document.querySelectorAll(".dropdown_menu_btn").forEach(function(btn) {
                btn.setAttribute("aria-expanded", "false");
            });
            child.mobileFocusEvent();
        };
        child.toggleDropdown = function(dropdownWrapper) {
            var menuToToggle = dropdownWrapper.querySelector(".dropdown_menu_list");
            var isOpening = !menuToToggle.classList.contains("on");
            var allDropdownWrappers = document.querySelectorAll(".dropdown_menu_wrap");
            allDropdownWrappers.forEach(function(wrapper) {
                if (wrapper !== dropdownWrapper) {
                    var menu = wrapper.querySelector(".dropdown_menu_list");
                    var btn = wrapper.querySelector(".dropdown_menu_btn");
                    if (menu) {
                        menu.classList.remove("on");
                        menu.setAttribute("aria-hidden", "true");
                        menu.querySelectorAll("[role='menuitem']").forEach(function(item) {
                            item.setAttribute("aria-hidden", "true");
                            item.setAttribute("tabindex", "-1");
                        });
                    }
                    if (btn) btn.setAttribute("aria-expanded", "false");
                }
            });
            menuToToggle.classList.toggle("on");
            var currentBtn = dropdownWrapper.querySelector(".dropdown_menu_btn");
            if (isOpening) {
                var _menuToToggle$querySe;
                if (currentBtn) {
                    currentBtn.setAttribute("aria-expanded", "true");
                }
                menuToToggle.setAttribute("aria-hidden", "false");
                menuToToggle.querySelectorAll("[role='menuitem']").forEach(function(item) {
                    item.setAttribute("aria-hidden", "false");
                    item.setAttribute("tabindex", "0");
                });
                (_menuToToggle$querySe = menuToToggle.querySelector("[role='menuitem']:nth-of-type(1)")) === null || _menuToToggle$querySe === void 0 || _menuToToggle$querySe.focus();
            } else {
                if (currentBtn) {
                    currentBtn.setAttribute("aria-expanded", "false");
                    currentBtn.focus();
                }
                menuToToggle.setAttribute("aria-hidden", "true");
                menuToToggle.querySelectorAll("[role='menuitem']").forEach(function(item) {
                    item.setAttribute("aria-hidden", "true");
                    item.setAttribute("tabindex", "-1");
                });
            }
        };
    })(EzauthMobileChild);
    const __WEBPACK_DEFAULT_EXPORT__ = EzauthMobileChild;
})();

window.EzAuthMAct = __webpack_exports__["default"];