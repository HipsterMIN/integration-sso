var __webpack_modules__ = {
    "./src/ezauth/js/EzauthConfig.js"(__unused_webpack_module, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        var _babel_runtime_helpers_asyncToGenerator__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/asyncToGenerator.js");
        var _babel_runtime_regenerator__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__("./node_modules/@babel/runtime/regenerator/index.js");
        var _babel_runtime_regenerator__WEBPACK_IMPORTED_MODULE_1___default = __webpack_require__.n(_babel_runtime_regenerator__WEBPACK_IMPORTED_MODULE_1__);
        var _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__("./src/ezauth/utils/Logger.js");
        window.EzauthConfig = {};
        (function() {
            var parseJSONC = function() {
                function parseJSONC(code, reviver) {
                    return JSON.parse(code.replace(/("(?:[^"\\]+|\\.)*")|\/\/[^\r\n]*|\/\*[^]*?\*\//g, get2ndOrSpaces).replace(/("([^"\\]+|\\.)*")|,\s*(?=[\}\]])/g, get2ndOrSpaces), reviver);
                }
                function get2ndOrSpaces(match, string) {
                    return string || match.replace(/[^\t\r\n ]/g, " ");
                }
                return parseJSONC;
            }();
            var configLoadPromise = null;
            function calculateEzauthRootPath(currentScriptSrc) {
                try {
                    var url = new URL(currentScriptSrc);
                    var pathParts = url.pathname.split("/");
                    if (pathParts[pathParts.length - 2] === "js") {
                        pathParts.splice(-2);
                        return url.origin + pathParts.join("/") + "/";
                    }
                    return url.origin + url.pathname.substring(0, url.pathname.lastIndexOf("/") + 1);
                } catch (error) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("ezauth 루트 경로 계산 실패");
                    throw new Error;
                }
            }
            function loadConfigInternal() {
                return _loadConfigInternal.apply(this, arguments);
            }
            function _loadConfigInternal() {
                _loadConfigInternal = (0, _babel_runtime_helpers_asyncToGenerator__WEBPACK_IMPORTED_MODULE_0__["default"])(_babel_runtime_regenerator__WEBPACK_IMPORTED_MODULE_1___default().mark(function _callee2() {
                    var _document$currentScri, currentScriptSrc, ezauthRootPath, scriptDir, configJsoncPath, response, jsoncText, config, _t;
                    return _babel_runtime_regenerator__WEBPACK_IMPORTED_MODULE_1___default().wrap(function(_context2) {
                        while (1) switch (_context2.prev = _context2.next) {
                          case 0:
                            _context2.prev = 0;
                            currentScriptSrc = ((_document$currentScri = document.currentScript) === null || _document$currentScri === void 0 ? void 0 : _document$currentScri.src) || "file:///F:/MagicLineHub/source/bankezauth-client-main/src/ezauth/js/EzauthConfig.js";
                            if (currentScriptSrc) {
                                _context2.next = 1;
                                break;
                            }
                            throw new Error("현재 스크립트 경로를 확인할 수 없습니다.");

                          case 1:
                            ezauthRootPath = calculateEzauthRootPath(currentScriptSrc);
                            scriptDir = currentScriptSrc.substring(0, currentScriptSrc.lastIndexOf("/"));
                            configJsoncPath = "".concat(scriptDir, "/../setting/EzauthConfig.json");
                            _context2.next = 2;
                            return fetch(configJsoncPath);

                          case 2:
                            response = _context2.sent;
                            if (response.ok) {
                                _context2.next = 3;
                                break;
                            }
                            throw new Error("설정 파일 로드 실패: ".concat(response.status, " ").concat(response.statusText));

                          case 3:
                            _context2.next = 4;
                            return response.text();

                          case 4:
                            jsoncText = _context2.sent;
                            config = parseJSONC(jsoncText);
                            config.ezauthRootPath = ezauthRootPath;
                            Object.assign(window.EzauthConfig, config);
                            _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].info("EzauthConfig 로드 완료");
                            _context2.next = 6;
                            break;

                          case 5:
                            _context2.prev = 5;
                            _t = _context2["catch"](0);
                            _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("EzauthConfig 로드 실패: ", _t);
                            throw new Error("간편인증 설정 파일을 로드할 수 없습니다: ".concat(_t.message));

                          case 6:
                          case "end":
                            return _context2.stop();
                        }
                    }, _callee2, null, [ [ 0, 5 ] ]);
                }));
                return _loadConfigInternal.apply(this, arguments);
            }
            configLoadPromise = loadConfigInternal();
            window.EzauthConfig.waitForLoad = (0, _babel_runtime_helpers_asyncToGenerator__WEBPACK_IMPORTED_MODULE_0__["default"])(_babel_runtime_regenerator__WEBPACK_IMPORTED_MODULE_1___default().mark(function _callee() {
                return _babel_runtime_regenerator__WEBPACK_IMPORTED_MODULE_1___default().wrap(function(_context) {
                    while (1) switch (_context.prev = _context.next) {
                      case 0:
                        _context.next = 1;
                        return configLoadPromise;

                      case 1:
                        return _context.abrupt("return", window.EzauthConfig);

                      case 2:
                      case "end":
                        return _context.stop();
                    }
                }, _callee);
            }));
        })();
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
    "./node_modules/@babel/runtime/helpers/OverloadYield.js"(module) {
        function _OverloadYield(e, d) {
            this.v = e, this.k = d;
        }
        module.exports = _OverloadYield, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/helpers/regenerator.js"(module, __unused_webpack_exports, __webpack_require__) {
        var regeneratorDefine = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorDefine.js");
        function _regenerator() {
            var e, t, r = "function" == typeof Symbol ? Symbol : {}, n = r.iterator || "@@iterator", o = r.toStringTag || "@@toStringTag";
            function i(r, n, o, i) {
                var c = n && n.prototype instanceof Generator ? n : Generator, u = Object.create(c.prototype);
                return regeneratorDefine(u, "_invoke", function(r, n, o) {
                    var i, c, u, f = 0, p = o || [], y = !1, G = {
                        p: 0,
                        n: 0,
                        v: e,
                        a: d,
                        f: d.bind(e, 4),
                        d: function d(t, r) {
                            return i = t, c = 0, u = e, G.n = r, a;
                        }
                    };
                    function d(r, n) {
                        for (c = r, u = n, t = 0; !y && f && !o && t < p.length; t++) {
                            var o, i = p[t], d = G.p, l = i[2];
                            r > 3 ? (o = l === n) && (u = i[(c = i[4]) ? 5 : (c = 3, 3)], i[4] = i[5] = e) : i[0] <= d && ((o = r < 2 && d < i[1]) ? (c = 0, 
                            G.v = n, G.n = i[1]) : d < l && (o = r < 3 || i[0] > n || n > l) && (i[4] = r, i[5] = n, 
                            G.n = l, c = 0));
                        }
                        if (o || r > 1) return a;
                        throw y = !0, n;
                    }
                    return function(o, p, l) {
                        if (f > 1) throw TypeError("Generator is already running");
                        for (y && 1 === p && d(p, l), c = p, u = l; (t = c < 2 ? e : u) || !y; ) {
                            i || (c ? c < 3 ? (c > 1 && (G.n = -1), d(c, u)) : G.n = u : G.v = u);
                            try {
                                if (f = 2, i) {
                                    if (c || (o = "next"), t = i[o]) {
                                        if (!(t = t.call(i, u))) throw TypeError("iterator result is not an object");
                                        if (!t.done) return t;
                                        u = t.value, c < 2 && (c = 0);
                                    } else 1 === c && (t = i["return"]) && t.call(i), c < 2 && (u = TypeError("The iterator does not provide a '" + o + "' method"), 
                                    c = 1);
                                    i = e;
                                } else if ((t = (y = G.n < 0) ? u : r.call(n, G)) !== a) break;
                            } catch (t) {
                                i = e, c = 1, u = t;
                            } finally {
                                f = 1;
                            }
                        }
                        return {
                            value: t,
                            done: y
                        };
                    };
                }(r, o, i), !0), u;
            }
            var a = {};
            function Generator() {}
            function GeneratorFunction() {}
            function GeneratorFunctionPrototype() {}
            t = Object.getPrototypeOf;
            var c = [][n] ? t(t([][n]())) : (regeneratorDefine(t = {}, n, function() {
                return this;
            }), t), u = GeneratorFunctionPrototype.prototype = Generator.prototype = Object.create(c);
            function f(e) {
                return Object.setPrototypeOf ? Object.setPrototypeOf(e, GeneratorFunctionPrototype) : (e.__proto__ = GeneratorFunctionPrototype, 
                regeneratorDefine(e, o, "GeneratorFunction")), e.prototype = Object.create(u), e;
            }
            return GeneratorFunction.prototype = GeneratorFunctionPrototype, regeneratorDefine(u, "constructor", GeneratorFunctionPrototype), 
            regeneratorDefine(GeneratorFunctionPrototype, "constructor", GeneratorFunction), 
            GeneratorFunction.displayName = "GeneratorFunction", regeneratorDefine(GeneratorFunctionPrototype, o, "GeneratorFunction"), 
            regeneratorDefine(u), regeneratorDefine(u, o, "Generator"), regeneratorDefine(u, n, function() {
                return this;
            }), regeneratorDefine(u, "toString", function() {
                return "[object Generator]";
            }), (module.exports = _regenerator = function _regenerator() {
                return {
                    w: i,
                    m: f
                };
            }, module.exports.__esModule = true, module.exports["default"] = module.exports)();
        }
        module.exports = _regenerator, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/helpers/regeneratorAsync.js"(module, __unused_webpack_exports, __webpack_require__) {
        var regeneratorAsyncGen = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorAsyncGen.js");
        function _regeneratorAsync(n, e, r, t, o) {
            var a = regeneratorAsyncGen(n, e, r, t, o);
            return a.next().then(function(n) {
                return n.done ? n.value : a.next();
            });
        }
        module.exports = _regeneratorAsync, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/helpers/regeneratorAsyncGen.js"(module, __unused_webpack_exports, __webpack_require__) {
        var regenerator = __webpack_require__("./node_modules/@babel/runtime/helpers/regenerator.js");
        var regeneratorAsyncIterator = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorAsyncIterator.js");
        function _regeneratorAsyncGen(r, e, t, o, n) {
            return new regeneratorAsyncIterator(regenerator().w(r, e, t, o), n || Promise);
        }
        module.exports = _regeneratorAsyncGen, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/helpers/regeneratorAsyncIterator.js"(module, __unused_webpack_exports, __webpack_require__) {
        var OverloadYield = __webpack_require__("./node_modules/@babel/runtime/helpers/OverloadYield.js");
        var regeneratorDefine = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorDefine.js");
        function AsyncIterator(t, e) {
            function n(r, o, i, f) {
                try {
                    var c = t[r](o), u = c.value;
                    return u instanceof OverloadYield ? e.resolve(u.v).then(function(t) {
                        n("next", t, i, f);
                    }, function(t) {
                        n("throw", t, i, f);
                    }) : e.resolve(u).then(function(t) {
                        c.value = t, i(c);
                    }, function(t) {
                        return n("throw", t, i, f);
                    });
                } catch (t) {
                    f(t);
                }
            }
            var r;
            this.next || (regeneratorDefine(AsyncIterator.prototype), regeneratorDefine(AsyncIterator.prototype, "function" == typeof Symbol && Symbol.asyncIterator || "@asyncIterator", function() {
                return this;
            })), regeneratorDefine(this, "_invoke", function(t, o, i) {
                function f() {
                    return new e(function(e, r) {
                        n(t, i, e, r);
                    });
                }
                return r = r ? r.then(f, f) : f();
            }, !0);
        }
        module.exports = AsyncIterator, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/helpers/regeneratorDefine.js"(module) {
        function _regeneratorDefine(e, r, n, t) {
            var i = Object.defineProperty;
            try {
                i({}, "", {});
            } catch (e) {
                i = 0;
            }
            module.exports = _regeneratorDefine = function regeneratorDefine(e, r, n, t) {
                function o(r, n) {
                    _regeneratorDefine(e, r, function(e) {
                        return this._invoke(r, n, e);
                    });
                }
                r ? i ? i(e, r, {
                    value: n,
                    enumerable: !t,
                    configurable: !t,
                    writable: !t
                }) : e[r] = n : (o("next", 0), o("throw", 1), o("return", 2));
            }, module.exports.__esModule = true, module.exports["default"] = module.exports, 
            _regeneratorDefine(e, r, n, t);
        }
        module.exports = _regeneratorDefine, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/helpers/regeneratorKeys.js"(module) {
        function _regeneratorKeys(e) {
            var n = Object(e), r = [];
            for (var t in n) r.unshift(t);
            return function e() {
                for (;r.length; ) if ((t = r.pop()) in n) return e.value = t, e.done = !1, e;
                return e.done = !0, e;
            };
        }
        module.exports = _regeneratorKeys, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/helpers/regeneratorRuntime.js"(module, __unused_webpack_exports, __webpack_require__) {
        var OverloadYield = __webpack_require__("./node_modules/@babel/runtime/helpers/OverloadYield.js");
        var regenerator = __webpack_require__("./node_modules/@babel/runtime/helpers/regenerator.js");
        var regeneratorAsync = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorAsync.js");
        var regeneratorAsyncGen = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorAsyncGen.js");
        var regeneratorAsyncIterator = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorAsyncIterator.js");
        var regeneratorKeys = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorKeys.js");
        var regeneratorValues = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorValues.js");
        function _regeneratorRuntime() {
            "use strict";
            var r = regenerator(), e = r.m(_regeneratorRuntime), t = (Object.getPrototypeOf ? Object.getPrototypeOf(e) : e.__proto__).constructor;
            function n(r) {
                var e = "function" == typeof r && r.constructor;
                return !!e && (e === t || "GeneratorFunction" === (e.displayName || e.name));
            }
            var o = {
                throw: 1,
                return: 2,
                break: 3,
                continue: 3
            };
            function a(r) {
                var e, t;
                return function(n) {
                    e || (e = {
                        stop: function stop() {
                            return t(n.a, 2);
                        },
                        catch: function _catch() {
                            return n.v;
                        },
                        abrupt: function abrupt(r, e) {
                            return t(n.a, o[r], e);
                        },
                        delegateYield: function delegateYield(r, o, a) {
                            return e.resultName = o, t(n.d, regeneratorValues(r), a);
                        },
                        finish: function finish(r) {
                            return t(n.f, r);
                        }
                    }, t = function t(r, _t, o) {
                        n.p = e.prev, n.n = e.next;
                        try {
                            return r(_t, o);
                        } finally {
                            e.next = n.n;
                        }
                    }), e.resultName && (e[e.resultName] = n.v, e.resultName = void 0), e.sent = n.v, 
                    e.next = n.n;
                    try {
                        return r.call(this, e);
                    } finally {
                        n.p = e.prev, n.n = e.next;
                    }
                };
            }
            return (module.exports = _regeneratorRuntime = function _regeneratorRuntime() {
                return {
                    wrap: function wrap(e, t, n, o) {
                        return r.w(a(e), t, n, o && o.reverse());
                    },
                    isGeneratorFunction: n,
                    mark: r.m,
                    awrap: function awrap(r, e) {
                        return new OverloadYield(r, e);
                    },
                    AsyncIterator: regeneratorAsyncIterator,
                    async: function async(r, e, t, o, u) {
                        return (n(e) ? regeneratorAsyncGen : regeneratorAsync)(a(r), e, t, o, u);
                    },
                    keys: regeneratorKeys,
                    values: regeneratorValues
                };
            }, module.exports.__esModule = true, module.exports["default"] = module.exports)();
        }
        module.exports = _regeneratorRuntime, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/helpers/regeneratorValues.js"(module, __unused_webpack_exports, __webpack_require__) {
        var _typeof = __webpack_require__("./node_modules/@babel/runtime/helpers/typeof.js")["default"];
        function _regeneratorValues(e) {
            if (null != e) {
                var t = e["function" == typeof Symbol && Symbol.iterator || "@@iterator"], r = 0;
                if (t) return t.call(e);
                if ("function" == typeof e.next) return e;
                if (!isNaN(e.length)) return {
                    next: function next() {
                        return e && r >= e.length && (e = void 0), {
                            value: e && e[r++],
                            done: !e
                        };
                    }
                };
            }
            throw new TypeError(_typeof(e) + " is not iterable");
        }
        module.exports = _regeneratorValues, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/helpers/typeof.js"(module) {
        function _typeof(o) {
            "@babel/helpers - typeof";
            return module.exports = _typeof = "function" == typeof Symbol && "symbol" == typeof Symbol.iterator ? function(o) {
                return typeof o;
            } : function(o) {
                return o && "function" == typeof Symbol && o.constructor === Symbol && o !== Symbol.prototype ? "symbol" : typeof o;
            }, module.exports.__esModule = true, module.exports["default"] = module.exports, 
            _typeof(o);
        }
        module.exports = _typeof, module.exports.__esModule = true, module.exports["default"] = module.exports;
    },
    "./node_modules/@babel/runtime/regenerator/index.js"(module, __unused_webpack_exports, __webpack_require__) {
        var runtime = __webpack_require__("./node_modules/@babel/runtime/helpers/regeneratorRuntime.js")();
        module.exports = runtime;
        try {
            regeneratorRuntime = runtime;
        } catch (accidentalStrictMode) {
            if (typeof globalThis === "object") {
                globalThis.regeneratorRuntime = runtime;
            } else {
                Function("r", "regeneratorRuntime = r")(runtime);
            }
        }
    },
    "./node_modules/@babel/runtime/helpers/esm/asyncToGenerator.js"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            default: () => _asyncToGenerator
        });
        function asyncGeneratorStep(n, t, e, r, o, a, c) {
            try {
                var i = n[a](c), u = i.value;
            } catch (n) {
                return void e(n);
            }
            i.done ? t(u) : Promise.resolve(u).then(r, o);
        }
        function _asyncToGenerator(n) {
            return function() {
                var t = this, e = arguments;
                return new Promise(function(r, o) {
                    var a = n.apply(t, e);
                    function _next(n) {
                        asyncGeneratorStep(a, r, o, _next, _throw, "next", n);
                    }
                    function _throw(n) {
                        asyncGeneratorStep(a, r, o, _next, _throw, "throw", n);
                    }
                    _next(void 0);
                });
            };
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
    "./node_modules/ua-parser-js/src/main/ua-parser.mjs"(__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) {
        "use strict";
        __webpack_require__.r(__webpack_exports__);
        __webpack_require__.d(__webpack_exports__, {
            UAParser: () => UAParser
        });
        var LIBVERSION = "2.0.9", UA_MAX_LENGTH = 500, USER_AGENT = "user-agent", EMPTY = "", UNKNOWN = "?", TYPEOF = {
            FUNCTION: "function",
            OBJECT: "object",
            STRING: "string",
            UNDEFINED: "undefined"
        }, BROWSER = "browser", CPU = "cpu", DEVICE = "device", ENGINE = "engine", OS = "os", RESULT = "result", NAME = "name", TYPE = "type", VENDOR = "vendor", VERSION = "version", ARCHITECTURE = "architecture", MAJOR = "major", MODEL = "model", CONSOLE = "console", MOBILE = "mobile", TABLET = "tablet", SMARTTV = "smarttv", WEARABLE = "wearable", XR = "xr", EMBEDDED = "embedded", FETCHER = "fetcher", INAPP = "inapp", BRANDS = "brands", FORMFACTORS = "formFactors", FULLVERLIST = "fullVersionList", PLATFORM = "platform", PLATFORMVER = "platformVersion", BITNESS = "bitness", CH = "sec-ch-ua", CH_FULL_VER_LIST = CH + "-full-version-list", CH_ARCH = CH + "-arch", CH_BITNESS = CH + "-" + BITNESS, CH_FORM_FACTORS = CH + "-form-factors", CH_MOBILE = CH + "-" + MOBILE, CH_MODEL = CH + "-" + MODEL, CH_PLATFORM = CH + "-" + PLATFORM, CH_PLATFORM_VER = CH_PLATFORM + "-version", CH_ALL_VALUES = [ BRANDS, FULLVERLIST, MOBILE, MODEL, PLATFORM, PLATFORMVER, ARCHITECTURE, FORMFACTORS, BITNESS ], AMAZON = "Amazon", APPLE = "Apple", ASUS = "ASUS", BLACKBERRY = "BlackBerry", GOOGLE = "Google", HUAWEI = "Huawei", LENOVO = "Lenovo", HONOR = "Honor", LG = "LG", MICROSOFT = "Microsoft", MOTOROLA = "Motorola", NVIDIA = "Nvidia", ONEPLUS = "OnePlus", OPPO = "OPPO", SAMSUNG = "Samsung", SHARP = "Sharp", SONY = "Sony", XIAOMI = "Xiaomi", ZEBRA = "Zebra", CHROME = "Chrome", CHROMIUM = "Chromium", CHROMECAST = "Chromecast", EDGE = "Edge", FIREFOX = "Firefox", OPERA = "Opera", FACEBOOK = "Facebook", SOGOU = "Sogou", PREFIX_MOBILE = "Mobile ", SUFFIX_BROWSER = " Browser", WINDOWS = "Windows";
        var isWindow = typeof window !== TYPEOF.UNDEFINED, NAVIGATOR = isWindow && window.navigator ? window.navigator : undefined, NAVIGATOR_UADATA = NAVIGATOR && NAVIGATOR.userAgentData ? NAVIGATOR.userAgentData : undefined;
        var extend = function(defaultRgx, extensions) {
            var mergedRgx = {};
            var extraRgx = extensions;
            if (!isExtensions(extensions)) {
                extraRgx = {};
                for (var i in extensions) {
                    for (var j in extensions[i]) {
                        extraRgx[j] = extensions[i][j].concat(extraRgx[j] ? extraRgx[j] : []);
                    }
                }
            }
            for (var k in defaultRgx) {
                mergedRgx[k] = extraRgx[k] && extraRgx[k].length % 2 === 0 ? extraRgx[k].concat(defaultRgx[k]) : defaultRgx[k];
            }
            return mergedRgx;
        }, enumerize = function(arr) {
            var enums = {};
            for (var i = 0; i < arr.length; i++) {
                enums[arr[i].toUpperCase()] = arr[i];
            }
            return enums;
        }, has = function(str1, str2) {
            if (typeof str1 === TYPEOF.OBJECT && str1.length > 0) {
                for (var i in str1) {
                    if (lowerize(str2) == lowerize(str1[i])) return true;
                }
                return false;
            }
            return isString(str1) ? lowerize(str2) == lowerize(str1) : false;
        }, isExtensions = function(obj, deep) {
            for (var prop in obj) {
                return /^(browser|cpu|device|engine|os)$/.test(prop) || (deep ? isExtensions(obj[prop]) : false);
            }
        }, isString = function(val) {
            return typeof val === TYPEOF.STRING;
        }, itemListToArray = function(header) {
            if (!header) return undefined;
            var arr = [];
            var tokens = strip(/\\?\"/g, header).split(",");
            for (var i = 0; i < tokens.length; i++) {
                if (tokens[i].indexOf(";") > -1) {
                    var token = trim(tokens[i]).split(";v=");
                    arr[i] = {
                        brand: token[0],
                        version: token[1]
                    };
                } else {
                    arr[i] = trim(tokens[i]);
                }
            }
            return arr;
        }, lowerize = function(str) {
            return isString(str) ? str.toLowerCase() : str;
        }, majorize = function(version) {
            return isString(version) ? strip(/[^\d\.]/g, version).split(".")[0] : undefined;
        }, setProps = function(arr) {
            for (var i in arr) {
                if (!arr.hasOwnProperty(i)) continue;
                var propName = arr[i];
                if (typeof propName == TYPEOF.OBJECT && propName.length == 2) {
                    this[propName[0]] = propName[1];
                } else {
                    this[propName] = undefined;
                }
            }
            return this;
        }, strip = function(pattern, str) {
            return isString(str) ? str.replace(pattern, EMPTY) : str;
        }, stripQuotes = function(str) {
            return strip(/\\?\"/g, str);
        }, trim = function(str, len) {
            str = strip(/^\s\s*/, String(str));
            return typeof len === TYPEOF.UNDEFINED ? str : str.substring(0, len);
        };
        var rgxMapper = function(ua, arrays) {
            if (!ua || !arrays) return;
            var i = 0, j, k, p, q, matches, match;
            while (i < arrays.length && !matches) {
                var regex = arrays[i], props = arrays[i + 1];
                j = k = 0;
                while (j < regex.length && !matches) {
                    if (!regex[j]) {
                        break;
                    }
                    matches = regex[j++].exec(ua);
                    if (!!matches) {
                        for (p = 0; p < props.length; p++) {
                            match = matches[++k];
                            q = props[p];
                            if (typeof q === TYPEOF.OBJECT && q.length > 0) {
                                if (q.length === 2) {
                                    if (typeof q[1] == TYPEOF.FUNCTION) {
                                        this[q[0]] = q[1].call(this, match);
                                    } else {
                                        this[q[0]] = q[1];
                                    }
                                } else if (q.length >= 3) {
                                    if (typeof q[1] === TYPEOF.FUNCTION && !(q[1].exec && q[1].test)) {
                                        if (q.length > 3) {
                                            this[q[0]] = match ? q[1].apply(this, q.slice(2)) : undefined;
                                        } else {
                                            this[q[0]] = match ? q[1].call(this, match, q[2]) : undefined;
                                        }
                                    } else {
                                        if (q.length == 3) {
                                            this[q[0]] = match ? match.replace(q[1], q[2]) : undefined;
                                        } else if (q.length == 4) {
                                            this[q[0]] = match ? q[3].call(this, match.replace(q[1], q[2])) : undefined;
                                        } else if (q.length > 4) {
                                            this[q[0]] = match ? q[3].apply(this, [ match.replace(q[1], q[2]) ].concat(q.slice(4))) : undefined;
                                        }
                                    }
                                }
                            } else {
                                this[q] = match ? match : undefined;
                            }
                        }
                    }
                }
                i += 2;
            }
        }, strMapper = function(str, map) {
            for (var i in map) {
                if (typeof map[i] === TYPEOF.OBJECT && map[i].length > 0) {
                    for (var j = 0; j < map[i].length; j++) {
                        if (has(map[i][j], str)) {
                            return i === UNKNOWN ? undefined : i;
                        }
                    }
                } else if (has(map[i], str)) {
                    return i === UNKNOWN ? undefined : i;
                }
            }
            return map.hasOwnProperty("*") ? map["*"] : str;
        };
        var windowsVersionMap = {
            ME: "4.90",
            "NT 3.51": "3.51",
            "NT 4.0": "4.0",
            2e3: [ "5.0", "5.01" ],
            XP: [ "5.1", "5.2" ],
            Vista: "6.0",
            7: "6.1",
            8: "6.2",
            8.1: "6.3",
            10: [ "6.4", "10.0" ],
            NT: ""
        }, formFactorsMap = {
            embedded: "Automotive",
            mobile: "Mobile",
            tablet: [ "Tablet", "EInk" ],
            smarttv: "TV",
            wearable: "Watch",
            xr: [ "VR", "XR" ],
            "?": [ "Desktop", "Unknown" ],
            "*": undefined
        }, browserHintsMap = {
            Chrome: "Google Chrome",
            Edge: "Microsoft Edge",
            "Edge WebView2": "Microsoft Edge WebView2",
            "Chrome WebView": "Android WebView",
            "Chrome Headless": "HeadlessChrome",
            "Huawei Browser": "HuaweiBrowser",
            "MIUI Browser": "Miui Browser",
            "Opera Mobi": "OperaMobile",
            Yandex: "YaBrowser"
        };
        var defaultRegexes = {
            browser: [ [ /\b(?:crmo|crios)\/([\w\.]+)/i ], [ VERSION, [ NAME, PREFIX_MOBILE + "Chrome" ] ], [ /webview.+edge\/([\w\.]+)/i ], [ VERSION, [ NAME, EDGE + " WebView" ] ], [ /edg(?:e|ios|a)?\/([\w\.]+)/i ], [ VERSION, [ NAME, "Edge" ] ], [ /(opera mini)\/([-\w\.]+)/i, /(opera [mobiletab]{3,6})\b.+version\/([-\w\.]+)/i, /(opera)(?:.+version\/|[\/ ]+)([\w\.]+)/i ], [ NAME, VERSION ], [ /opios[\/ ]+([\w\.]+)/i ], [ VERSION, [ NAME, OPERA + " Mini" ] ], [ /\bop(?:rg)?x\/([\w\.]+)/i ], [ VERSION, [ NAME, OPERA + " GX" ] ], [ /\bopr\/([\w\.]+)/i ], [ VERSION, [ NAME, OPERA ] ], [ /\bb[ai]*d(?:uhd|[ub]*[aekoprswx]{5,6})[\/ ]?([\w\.]+)/i ], [ VERSION, [ NAME, "Baidu" ] ], [ /\b(?:mxbrowser|mxios|myie2)\/?([-\w\.]*)\b/i ], [ VERSION, [ NAME, "Maxthon" ] ], [ /(kindle)\/([\w\.]+)/i, /(lunascape|maxthon|netfront|jasmine|blazer|sleipnir)[\/ ]?([\w\.]*)/i, /(avant|iemobile|slim(?:browser|boat|jet))[\/ ]?([\d\.]*)/i, /(?:ms|\()(ie) ([\w\.]+)/i, /(atlas|flock|rockmelt|midori|epiphany|silk|skyfire|bolt|iron|vivaldi|iridium|phantomjs|bowser|qupzilla|falkon|rekonq|puffin|whale(?!.+naver)|qqbrowserlite|duckduckgo|klar|helio|(?=comodo_)?dragon|otter|dooble|(?:hi|lg |ovi|qute)browser|palemoon)\/v?([-\w\.]+)/i, /(brave)(?: chrome)?\/([\d\.]+)/i, /(aloha|heytap|ovi|115|surf|qwant)browser\/([\d\.]+)/i, /(qwant)(?:ios|mobile)\/([\d\.]+)/i, /(ecosia|weibo)(?:__| \w+@)([\d\.]+)/i ], [ NAME, VERSION ], [ /quark(?:pc)?\/([-\w\.]+)/i ], [ VERSION, [ NAME, "Quark" ] ], [ /\bddg\/([\w\.]+)/i ], [ VERSION, [ NAME, "DuckDuckGo" ] ], [ /(?:\buc? ?browser|(?:juc.+)ucweb)[\/ ]?([\w\.]+)/i ], [ VERSION, [ NAME, "UCBrowser" ] ], [ /microm.+\bqbcore\/([\w\.]+)/i, /\bqbcore\/([\w\.]+).+microm/i, /micromessenger\/([\w\.]+)/i ], [ VERSION, [ NAME, "WeChat" ] ], [ /konqueror\/([\w\.]+)/i ], [ VERSION, [ NAME, "Konqueror" ] ], [ /trident.+rv[: ]([\w\.]{1,9})\b.+like gecko/i ], [ VERSION, [ NAME, "IE" ] ], [ /ya(?:search)?browser\/([\w\.]+)/i ], [ VERSION, [ NAME, "Yandex" ] ], [ /slbrowser\/([\w\.]+)/i ], [ VERSION, [ NAME, "Smart " + LENOVO + SUFFIX_BROWSER ] ], [ /(av(?:ast|g|ira))\/([\w\.]+)/i ], [ [ NAME, /(.+)/, "$1 Secure" + SUFFIX_BROWSER ], VERSION ], [ /norton\/([\w\.]+)/i ], [ VERSION, [ NAME, "Norton Private" + SUFFIX_BROWSER ] ], [ /\bfocus\/([\w\.]+)/i ], [ VERSION, [ NAME, FIREFOX + " Focus" ] ], [ / mms\/([\w\.]+)$/i ], [ VERSION, [ NAME, OPERA + " Neon" ] ], [ / opt\/([\w\.]+)$/i ], [ VERSION, [ NAME, OPERA + " Touch" ] ], [ /coc_coc\w+\/([\w\.]+)/i ], [ VERSION, [ NAME, "Coc Coc" ] ], [ /dolfin\/([\w\.]+)/i ], [ VERSION, [ NAME, "Dolphin" ] ], [ /coast\/([\w\.]+)/i ], [ VERSION, [ NAME, OPERA + " Coast" ] ], [ /miuibrowser\/([\w\.]+)/i ], [ VERSION, [ NAME, "MIUI" + SUFFIX_BROWSER ] ], [ /fxios\/([\w\.-]+)/i ], [ VERSION, [ NAME, PREFIX_MOBILE + FIREFOX ] ], [ /\bqihoobrowser\/?([\w\.]*)/i ], [ VERSION, [ NAME, "360" ] ], [ /\b(qq)\/([\w\.]+)/i ], [ [ NAME, /(.+)/, "$1Browser" ], VERSION ], [ /(oculus|sailfish|huawei|vivo|pico)browser\/([\w\.]+)/i ], [ [ NAME, /(.+)/, "$1" + SUFFIX_BROWSER ], VERSION ], [ /samsungbrowser\/([\w\.]+)/i ], [ VERSION, [ NAME, SAMSUNG + " Internet" ] ], [ /metasr[\/ ]?([\d\.]+)/i ], [ VERSION, [ NAME, SOGOU + " Explorer" ] ], [ /(sogou)mo\w+\/([\d\.]+)/i ], [ [ NAME, SOGOU + " Mobile" ], VERSION ], [ /(electron)\/([\w\.]+) safari/i, /(tesla)(?: qtcarbrowser|\/(20\d\d\.[-\w\.]+))/i, /m?(qqbrowser|2345(?=browser|chrome|explorer))\w*[\/ ]?v?([\w\.]+)/i ], [ NAME, VERSION ], [ /(lbbrowser|luakit|rekonq|steam(?= (clie|tenf|gameo)))/i ], [ NAME ], [ /ome\/([\w\.]+).+(iron(?= saf)|360(?=[es]e$))/i ], [ VERSION, NAME ], [ /((?:fban\/fbios|fb_iab\/fb4a)(?!.+fbav)|;fbav\/([\w\.]+);)/i ], [ [ NAME, FACEBOOK ], VERSION, [ TYPE, INAPP ] ], [ /(kakao(?:talk|story))[\/ ]([\w\.]+)/i, /(naver)\(.*?(\d+\.[\w\.]+).*\)/i, /(daum)apps[\/ ]([\w\.]+)/i, /safari (line)\/([\w\.]+)/i, /\b(line)\/([\w\.]+)\/iab/i, /(alipay)client\/([\w\.]+)/i, /(twitter)(?:and| f.+e\/([\w\.]+))/i, /(bing)(?:web|sapphire)\/([\w\.]+)/i, /(instagram|snapchat|klarna)[\/ ]([-\w\.]+)/i ], [ NAME, VERSION, [ TYPE, INAPP ] ], [ /\bgsa\/([\w\.]+) .*safari\//i ], [ VERSION, [ NAME, "GSA" ], [ TYPE, INAPP ] ], [ /(?:musical_ly|trill)(?:.+app_?version\/|_)([\w\.]+)/i ], [ VERSION, [ NAME, "TikTok" ], [ TYPE, INAPP ] ], [ /\[(linkedin)app\]/i ], [ NAME, [ TYPE, INAPP ] ], [ /(zalo(?:app)?)[\/\sa-z]*([\w\.-]+)/i ], [ [ NAME, /(.+)/, "Zalo" ], VERSION, [ TYPE, INAPP ] ], [ /(chromium)[\/ ]([-\w\.]+)/i ], [ NAME, VERSION ], [ /ome-(lighthouse)$/i ], [ NAME, [ TYPE, FETCHER ] ], [ /headlesschrome(?:\/([\w\.]+)| )/i ], [ VERSION, [ NAME, CHROME + " Headless" ] ], [ /wv\).+chrome\/([\w\.]+).+edgw\//i ], [ VERSION, [ NAME, EDGE + " WebView2" ] ], [ / wv\).+(chrome)\/([\w\.]+)/i ], [ [ NAME, CHROME + " WebView" ], VERSION ], [ /droid.+ version\/([\w\.]+)\b.+(?:mobile safari|safari)/i ], [ VERSION, [ NAME, "Android" + SUFFIX_BROWSER ] ], [ /chrome\/([\w\.]+) mobile/i ], [ VERSION, [ NAME, PREFIX_MOBILE + "Chrome" ] ], [ /(chrome|omniweb|arora|[tizenoka]{5} ?browser)\/v?([\w\.]+)/i ], [ NAME, VERSION ], [ /version\/([\w\.\,]+) .*mobile(?:\/\w+ | ?)safari/i ], [ VERSION, [ NAME, PREFIX_MOBILE + "Safari" ] ], [ /iphone .*mobile(?:\/\w+ | ?)safari/i ], [ [ NAME, PREFIX_MOBILE + "Safari" ] ], [ /version\/([\w\.\,]+) .*(safari)/i ], [ VERSION, NAME ], [ /webkit.+?(mobile ?safari|safari)(\/[\w\.]+)/i ], [ NAME, [ VERSION, "1" ] ], [ /(webkit|khtml)\/([\w\.]+)/i ], [ NAME, VERSION ], [ /(?:mobile|tablet);.*(firefox)\/([\w\.-]+)/i ], [ [ NAME, PREFIX_MOBILE + FIREFOX ], VERSION ], [ /(navigator|netscape\d?)\/([-\w\.]+)/i ], [ [ NAME, "Netscape" ], VERSION ], [ /(wolvic|librewolf)\/([\w\.]+)/i ], [ NAME, VERSION ], [ /mobile vr; rv:([\w\.]+)\).+firefox/i ], [ VERSION, [ NAME, FIREFOX + " Reality" ] ], [ /ekiohf.+(flow)\/([\w\.]+)/i, /(swiftfox)/i, /(icedragon|iceweasel|camino|chimera|fennec|maemo browser|minimo|conkeror)[\/ ]?([\w\.\+]+)/i, /(seamonkey|k-meleon|icecat|iceape|firebird|phoenix|basilisk|waterfox)\/([-\w\.]+)$/i, /(firefox)\/([\w\.]+)/i, /(mozilla)\/([\w\.]+(?= .+rv\:.+gecko\/\d+)|[0-4][\w\.]+(?!.+compatible))/i, /(amaya|dillo|doris|icab|ladybird|lynx|mosaic|netsurf|obigo|polaris|w3m|(?:go|ice|up)[\. ]?browser)[-\/ ]?v?([\w\.]+)/i, /\b(links) \(([\w\.]+)/i ], [ NAME, [ VERSION, /_/g, "." ] ], [ /(cobalt)\/([\w\.]+)/i ], [ NAME, [ VERSION, /[^\d\.]+./, EMPTY ] ] ],
            cpu: [ [ /\b((amd|x|x86[-_]?|wow|win)64)\b/i ], [ [ ARCHITECTURE, "amd64" ] ], [ /(ia32(?=;))/i, /\b((i[346]|x)86)(pc)?\b/i ], [ [ ARCHITECTURE, "ia32" ] ], [ /\b(aarch64|arm(v?[89]e?l?|_?64))\b/i ], [ [ ARCHITECTURE, "arm64" ] ], [ /\b(arm(v[67])?ht?n?[fl]p?)\b/i ], [ [ ARCHITECTURE, "armhf" ] ], [ /( (ce|mobile); ppc;|\/[\w\.]+arm\b)/i ], [ [ ARCHITECTURE, "arm" ] ], [ / sun4\w[;\)]/i ], [ [ ARCHITECTURE, "sparc" ] ], [ /\b(avr32|ia64(?=;)|68k(?=\))|\barm(?=v([1-7]|[5-7]1)l?|;|eabi)|(irix|mips|sparc)(64)?\b|pa-risc)/i, /((ppc|powerpc)(64)?)( mac|;|\))/i, /(?:osf1|[freopnt]{3,4}bsd) (alpha)/i ], [ [ ARCHITECTURE, /ower/, EMPTY, lowerize ] ], [ /mc680.0/i ], [ [ ARCHITECTURE, "68k" ] ], [ /winnt.+\[axp/i ], [ [ ARCHITECTURE, "alpha" ] ] ],
            device: [ [ /\b(sch-i[89]0\d|shw-m380s|sm-[ptx]\w{2,4}|gt-[pn]\d{2,4}|sgh-t8[56]9|nexus 10)/i ], [ MODEL, [ VENDOR, SAMSUNG ], [ TYPE, TABLET ] ], [ /\b((?:s[cgp]h|gt|sm)-(?![lr])\w+|sc[g-]?[\d]+a?|galaxy nexus)/i, /samsung[- ]((?!sm-[lr]|browser)[-\w]+)/i, /sec-(sgh\w+)/i ], [ MODEL, [ VENDOR, SAMSUNG ], [ TYPE, MOBILE ] ], [ /(?:\/|\()(ip(?:hone|od)[\w, ]*)[\/\);]/i ], [ MODEL, [ VENDOR, APPLE ], [ TYPE, MOBILE ] ], [ /\b(?:ios|apple\w+)\/.+[\(\/](ipad)/i, /\b(ipad)[\d,]*[;\] ].+(mac |i(pad)?)os/i ], [ MODEL, [ VENDOR, APPLE ], [ TYPE, TABLET ] ], [ /(macintosh);/i ], [ MODEL, [ VENDOR, APPLE ] ], [ /\b(sh-?[altvz]?\d\d[a-ekm]?)/i ], [ MODEL, [ VENDOR, SHARP ], [ TYPE, MOBILE ] ], [ /\b((?:brt|eln|hey2?|gdi|jdn)-a?[lnw]09|(?:ag[rm]3?|jdn2|kob2)-a?[lw]0[09]hn)(?: bui|\)|;)/i ], [ MODEL, [ VENDOR, HONOR ], [ TYPE, TABLET ] ], [ /honor([-\w ]+)[;\)]/i ], [ MODEL, [ VENDOR, HONOR ], [ TYPE, MOBILE ] ], [ /\b((?:ag[rs][2356]?k?|bah[234]?|bg[2o]|bt[kv]|cmr|cpn|db[ry]2?|jdn2|got|kob2?k?|mon|pce|scm|sht?|[tw]gr|vrd)-[ad]?[lw][0125][09]b?|605hw|bg2-u03|(?:gem|fdr|m2|ple|t1)-[7a]0[1-4][lu]|t1-a2[13][lw]|mediapad[\w\. ]*(?= bui|\)))\b(?!.+d\/s)/i ], [ MODEL, [ VENDOR, HUAWEI ], [ TYPE, TABLET ] ], [ /(?:huawei) ?([-\w ]+)[;\)]/i, /\b(nexus 6p|\w{2,4}e?-[atu]?[ln][\dx][\dc][adnt]?)\b(?!.+d\/s)/i ], [ MODEL, [ VENDOR, HUAWEI ], [ TYPE, MOBILE ] ], [ /oid[^\)]+; (2[\dbc]{4}(182|283|rp\w{2})[cgl]|m2105k81a?c)(?: bui|\))/i, /\b(?:xiao)?((?:red)?mi[-_ ]?pad[\w- ]*)(?: bui|\))/i ], [ [ MODEL, /_/g, " " ], [ VENDOR, XIAOMI ], [ TYPE, TABLET ] ], [ /\b; (\w+) build\/hm\1/i, /\b(hm[-_ ]?note?[_ ]?(?:\d\w)?) bui/i, /oid[^\)]+; (redmi[\-_ ]?(?:note|k)?[\w_ ]+|m?[12]\d[01]\d\w{3,6}|poco[\w ]+|(shark )?\w{3}-[ah]0|qin ?[1-3](s\+|ultra| pro)?)( bui|; wv|\))/i, /\b(mi[-_ ]?(?:a\d|one|one[_ ]plus|note|max|cc)?[_ ]?(?:\d{0,2}\w?)[_ ]?(?:plus|se|lite|pro)?( 5g|lte)?)(?: bui|\))/i, / ([\w ]+) miui\/v?\d/i ], [ [ MODEL, /_/g, " " ], [ VENDOR, XIAOMI ], [ TYPE, MOBILE ] ], [ /droid.+; (cph2[3-6]\d[13579]|((gm|hd)19|(ac|be|in|kb)20|(d[en]|eb|le|mt)21|ne22)[0-2]\d|p[g-l]\w[1m]10)\b/i, /(?:one)?(?:plus)? (a\d0\d\d)(?: b|\))/i ], [ MODEL, [ VENDOR, ONEPLUS ], [ TYPE, MOBILE ] ], [ /; (\w+) bui.+ oppo/i, /\b(cph[12]\d{3}|p(?:af|c[al]|d\w|e[ar])[mt]\d0|x9007|a101op)\b/i ], [ MODEL, [ VENDOR, OPPO ], [ TYPE, MOBILE ] ], [ /\b(opd2(\d{3}a?))(?: bui|\))/i ], [ MODEL, [ VENDOR, strMapper, {
                OnePlus: [ "203", "304", "403", "404", "413", "415" ],
                "*": OPPO
            } ], [ TYPE, TABLET ] ], [ /(vivo (5r?|6|8l?|go|one|s|x[il]?[2-4]?)[\w\+ ]*)(?: bui|\))/i ], [ MODEL, [ VENDOR, "BLU" ], [ TYPE, MOBILE ] ], [ /; vivo (\w+)(?: bui|\))/i, /\b(v[12]\d{3}\w?[at])(?: bui|;)/i ], [ MODEL, [ VENDOR, "Vivo" ], [ TYPE, MOBILE ] ], [ /\b(rmx[1-3]\d{3})(?: bui|;|\))/i ], [ MODEL, [ VENDOR, "Realme" ], [ TYPE, MOBILE ] ], [ /(ideatab[-\w ]+|602lv|d-42a|a101lv|a2109a|a3500-hv|s[56]000|pb-6505[my]|tb-?x?\d{3,4}(?:f[cu]|xu|[av])|yt\d?-[jx]?\d+[lfmx])( bui|;|\)|\/)/i, /lenovo ?(b[68]0[08]0-?[hf]?|tab(?:[\w- ]+?)|tb[\w-]{6,7})( bui|;|\)|\/)/i ], [ MODEL, [ VENDOR, LENOVO ], [ TYPE, TABLET ] ], [ /lenovo[-_ ]?([-\w ]+?)(?: bui|\)|\/)/i ], [ MODEL, [ VENDOR, LENOVO ], [ TYPE, MOBILE ] ], [ /\b(milestone|droid(?:[2-4x]| (?:bionic|x2|pro|razr))?:?( 4g)?)\b[\w ]+build\//i, /\bmot(?:orola)?[- ]([\w\s]+)(\)| bui)/i, /((?:moto(?! 360)[-\w\(\) ]+|xt\d{3,4}[cgkosw\+]?[-\d]*|nexus 6)(?= bui|\)))/i ], [ MODEL, [ VENDOR, MOTOROLA ], [ TYPE, MOBILE ] ], [ /\b(mz60\d|xoom[2 ]{0,2}) build\//i ], [ MODEL, [ VENDOR, MOTOROLA ], [ TYPE, TABLET ] ], [ /\b(?:lg)?([vl]k\-?\d{3}) bui| 3\.[-\w; ]{10}lg?-([06cv9]{3,4})/i ], [ MODEL, [ VENDOR, LG ], [ TYPE, TABLET ] ], [ /(lm(?:-?f100[nv]?|-[\w\.]+)(?= bui|\))|nexus [45])/i, /\blg[-e;\/ ]+(?!.*(?:browser|netcast|android tv|watch|webos))(\w+)/i, /\blg-?([\d\w]+) bui/i ], [ MODEL, [ VENDOR, LG ], [ TYPE, MOBILE ] ], [ /(nokia) (t[12][01])/i ], [ VENDOR, MODEL, [ TYPE, TABLET ] ], [ /(?:maemo|nokia).*(n900|lumia \d+|rm-\d+)/i, /nokia[-_ ]?(([-\w\. ]*?))( bui|\)|;|\/)/i ], [ [ MODEL, /_/g, " " ], [ TYPE, MOBILE ], [ VENDOR, "Nokia" ] ], [ /(pixel (c|tablet))\b/i ], [ MODEL, [ VENDOR, GOOGLE ], [ TYPE, TABLET ] ], [ /droid.+;(?: google)? (g(01[13]a|020[aem]|025[jn]|1b60|1f8f|2ybb|4s1m|576d|5nz6|8hhn|8vou|a02099|c15s|d1yq|e2ae|ec77|gh2x|kv4x|p4bc|pj41|r83y|tt9q|ur25|wvk6)|pixel[\d ]*a?( pro)?( xl)?( fold)?( \(5g\))?)( bui|\))/i ], [ MODEL, [ VENDOR, GOOGLE ], [ TYPE, MOBILE ] ], [ /(google) (pixelbook( go)?)/i ], [ VENDOR, MODEL ], [ /droid.+; (a?\d[0-2]{2}so|[c-g]\d{4}|so[-gl]\w+|xq-\w\w\d\d)(?= bui|\).+chrome\/(?![1-6]{0,1}\d\.))/i ], [ MODEL, [ VENDOR, SONY ], [ TYPE, MOBILE ] ], [ /sony tablet [ps]/i, /\b(?:sony)?sgp\w+(?: bui|\))/i ], [ [ MODEL, "Xperia Tablet" ], [ VENDOR, SONY ], [ TYPE, TABLET ] ], [ /(alexa)webm/i, /(kf[a-z]{2}wi|aeo(?!bc)\w\w)( bui|\))/i, /(kf[a-z]+)( bui|\)).+silk\//i ], [ MODEL, [ VENDOR, AMAZON ], [ TYPE, TABLET ] ], [ /((?:sd|kf)[0349hijorstuw]+)( bui|\)).+silk\//i ], [ [ MODEL, /(.+)/g, "Fire Phone $1" ], [ VENDOR, AMAZON ], [ TYPE, MOBILE ] ], [ /(playbook);[-\w\),; ]+(rim)/i ], [ MODEL, VENDOR, [ TYPE, TABLET ] ], [ /\b((?:bb[a-f]|st[hv])100-\d)/i, /(?:blackberry|\(bb10;) (\w+)/i ], [ MODEL, [ VENDOR, BLACKBERRY ], [ TYPE, MOBILE ] ], [ /(?:\b|asus_)(transfo[prime ]{4,10} \w+|eeepc|slider \w+|nexus 7|padfone|p00[cj])/i ], [ MODEL, [ VENDOR, ASUS ], [ TYPE, TABLET ] ], [ / (z[bes]6[027][012][km][ls]|zenfone \d\w?)\b/i ], [ MODEL, [ VENDOR, ASUS ], [ TYPE, MOBILE ] ], [ /(nexus 9)/i ], [ MODEL, [ VENDOR, "HTC" ], [ TYPE, TABLET ] ], [ /(htc)[-;_ ]{1,2}([\w ]+(?=\)| bui)|\w+)/i, /(zte)[- ]([\w ]+?)(?: bui|\/|\))/i, /(alcatel|geeksphone|nexian|panasonic(?!(?:;|\.))|sony(?!-bra))[-_ ]?([-\w]*)/i ], [ VENDOR, [ MODEL, /_/g, " " ], [ TYPE, MOBILE ] ], [ /tcl (xess p17aa)/i, /droid [\w\.]+; ((?:8[14]9[16]|9(?:0(?:48|60|8[01])|1(?:3[27]|66)|2(?:6[69]|9[56])|466))[gqswx])(_\w(\w|\w\w))?(\)| bui)/i ], [ MODEL, [ VENDOR, "TCL" ], [ TYPE, TABLET ] ], [ /droid [\w\.]+; (418(?:7d|8v)|5087z|5102l|61(?:02[dh]|25[adfh]|27[ai]|56[dh]|59k|65[ah])|a509dl|t(?:43(?:0w|1[adepqu])|50(?:6d|7[adju])|6(?:09dl|10k|12b|71[efho]|76[hjk])|7(?:66[ahju]|67[hw]|7[045][bh]|71[hk]|73o|76[ho]|79w|81[hks]?|82h|90[bhsy]|99b)|810[hs]))(_\w(\w|\w\w))?(\)| bui)/i ], [ MODEL, [ VENDOR, "TCL" ], [ TYPE, MOBILE ] ], [ /(itel) ((\w+))/i ], [ [ VENDOR, lowerize ], MODEL, [ TYPE, strMapper, {
                tablet: [ "p10001l", "w7001" ],
                "*": "mobile"
            } ] ], [ /droid.+; ([ab][1-7]-?[0178a]\d\d?)/i ], [ MODEL, [ VENDOR, "Acer" ], [ TYPE, TABLET ] ], [ /droid.+; (m[1-5] note) bui/i, /\bmz-([-\w]{2,})/i ], [ MODEL, [ VENDOR, "Meizu" ], [ TYPE, MOBILE ] ], [ /; ((?:power )?armor(?:[\w ]{0,8}))(?: bui|\))/i ], [ MODEL, [ VENDOR, "Ulefone" ], [ TYPE, MOBILE ] ], [ /; (energy ?\w+)(?: bui|\))/i, /; energizer ([\w ]+)(?: bui|\))/i ], [ MODEL, [ VENDOR, "Energizer" ], [ TYPE, MOBILE ] ], [ /; cat (b35);/i, /; (b15q?|s22 flip|s48c|s62 pro)(?: bui|\))/i ], [ MODEL, [ VENDOR, "Cat" ], [ TYPE, MOBILE ] ], [ /((?:new )?andromax[\w- ]+)(?: bui|\))/i ], [ MODEL, [ VENDOR, "Smartfren" ], [ TYPE, MOBILE ] ], [ /droid.+; (a(in)?(0(15|59|6[35])|142)p?)/i ], [ MODEL, [ VENDOR, "Nothing" ], [ TYPE, MOBILE ] ], [ /; (x67 5g|tikeasy \w+|ac[1789]\d\w+)( b|\))/i, /archos ?(5|gamepad2?|([\w ]*[t1789]|hello) ?\d+[\w ]*)( b|\))/i ], [ MODEL, [ VENDOR, "Archos" ], [ TYPE, TABLET ] ], [ /archos ([\w ]+)( b|\))/i, /; (ac[3-6]\d\w{2,8})( b|\))/i ], [ MODEL, [ VENDOR, "Archos" ], [ TYPE, MOBILE ] ], [ /; (n159v)/i ], [ MODEL, [ VENDOR, "HMD" ], [ TYPE, MOBILE ] ], [ /(imo) (tab \w+)/i, /(infinix|tecno) (x1101b?|p904|dp(7c|8d|10a)( pro)?|p70[1-3]a?|p904|t1101)/i ], [ VENDOR, MODEL, [ TYPE, TABLET ] ], [ /(blackberry|benq|palm(?=\-)|sonyericsson|acer|asus(?! zenw)|dell|jolla|meizu|motorola|polytron|tecno|micromax|advan)[-_ ]?([-\w]*)/i, /; (blu|hmd|imo|infinix|lava|oneplus|tcl|wiko)[_ ]([\w\+ ]+?)(?: bui|\)|; r)/i, /(hp) ([\w ]+\w)/i, /(microsoft); (lumia[\w ]+)/i, /(oppo) ?([\w ]+) bui/i, /(hisense) ([ehv][\w ]+)\)/i, /droid[^;]+; (philips)[_ ]([sv-x][\d]{3,4}[xz]?)/i ], [ VENDOR, MODEL, [ TYPE, MOBILE ] ], [ /(kobo)\s(ereader|touch)/i, /(hp).+(touchpad(?!.+tablet)|tablet)/i, /(kindle)\/([\w\.]+)/i ], [ VENDOR, MODEL, [ TYPE, TABLET ] ], [ /(surface duo)/i ], [ MODEL, [ VENDOR, MICROSOFT ], [ TYPE, TABLET ] ], [ /droid [\d\.]+; (fp\du?)(?: b|\))/i ], [ MODEL, [ VENDOR, "Fairphone" ], [ TYPE, MOBILE ] ], [ /((?:tegranote|shield t(?!.+d tv))[\w- ]*?)(?: b|\))/i ], [ MODEL, [ VENDOR, NVIDIA ], [ TYPE, TABLET ] ], [ /(sprint) (\w+)/i ], [ VENDOR, MODEL, [ TYPE, MOBILE ] ], [ /(kin\.[onetw]{3})/i ], [ [ MODEL, /\./g, " " ], [ VENDOR, MICROSOFT ], [ TYPE, MOBILE ] ], [ /droid.+; ([c6]+|et5[16]|mc[239][23]x?|vc8[03]x?)\)/i ], [ MODEL, [ VENDOR, ZEBRA ], [ TYPE, TABLET ] ], [ /droid.+; (ec30|ps20|tc[2-8]\d[kx])\)/i ], [ MODEL, [ VENDOR, ZEBRA ], [ TYPE, MOBILE ] ], [ /(philips)[\w ]+tv/i, /smart-tv.+(samsung)/i ], [ VENDOR, [ TYPE, SMARTTV ] ], [ /hbbtv.+maple;(\d+)/i ], [ [ MODEL, /^/, "SmartTV" ], [ VENDOR, SAMSUNG ], [ TYPE, SMARTTV ] ], [ /(vizio)(?: |.+model\/)(\w+-\w+)/i, /tcast.+(lg)e?. ([-\w]+)/i ], [ VENDOR, MODEL, [ TYPE, SMARTTV ] ], [ /(nux; netcast.+smarttv|lg (netcast\.tv-201\d|android tv))/i ], [ [ VENDOR, LG ], [ TYPE, SMARTTV ] ], [ /(apple) ?tv/i ], [ VENDOR, [ MODEL, APPLE + " TV" ], [ TYPE, SMARTTV ] ], [ /crkey.*devicetype\/chromecast/i ], [ [ MODEL, CHROMECAST + " Third Generation" ], [ VENDOR, GOOGLE ], [ TYPE, SMARTTV ] ], [ /crkey.*devicetype\/([^/]*)/i ], [ [ MODEL, /^/, "Chromecast " ], [ VENDOR, GOOGLE ], [ TYPE, SMARTTV ] ], [ /fuchsia.*crkey/i ], [ [ MODEL, CHROMECAST + " Nest Hub" ], [ VENDOR, GOOGLE ], [ TYPE, SMARTTV ] ], [ /crkey/i ], [ [ MODEL, CHROMECAST ], [ VENDOR, GOOGLE ], [ TYPE, SMARTTV ] ], [ /(portaltv)/i ], [ MODEL, [ VENDOR, FACEBOOK ], [ TYPE, SMARTTV ] ], [ /droid.+aft(\w+)( bui|\))/i ], [ MODEL, [ VENDOR, AMAZON ], [ TYPE, SMARTTV ] ], [ /(shield \w+ tv)/i ], [ MODEL, [ VENDOR, NVIDIA ], [ TYPE, SMARTTV ] ], [ /\(dtv[\);].+(aquos)/i, /(aquos-tv[\w ]+)\)/i ], [ MODEL, [ VENDOR, SHARP ], [ TYPE, SMARTTV ] ], [ /(bravia[\w ]+)( bui|\))/i ], [ MODEL, [ VENDOR, SONY ], [ TYPE, SMARTTV ] ], [ /(mi(tv|box)-?\w+) bui/i ], [ MODEL, [ VENDOR, XIAOMI ], [ TYPE, SMARTTV ] ], [ /Hbbtv.*(technisat) (.*);/i ], [ VENDOR, MODEL, [ TYPE, SMARTTV ] ], [ /\b(roku)[\dx]*[\)\/]((?:dvp-)?[\d\.]*)/i, /hbbtv\/\d+\.\d+\.\d+ +\([\w\+ ]*; *([\w\d][^;]*);([^;]*)/i ], [ [ VENDOR, /.+\/(\w+)/, "$1", strMapper, {
                LG: "lge"
            } ], [ MODEL, trim ], [ TYPE, SMARTTV ] ], [ /(playstation \w+)/i ], [ MODEL, [ VENDOR, SONY ], [ TYPE, CONSOLE ] ], [ /\b(xbox(?: one)?(?!; xbox))[\); ]/i ], [ MODEL, [ VENDOR, MICROSOFT ], [ TYPE, CONSOLE ] ], [ /(ouya)/i, /(nintendo) (\w+)/i, /(retroid) (pocket ([^\)]+))/i, /(valve).+(steam deck)/i, /droid.+; ((shield|rgcube|gr0006))( bui|\))/i ], [ [ VENDOR, strMapper, {
                Nvidia: "Shield",
                Anbernic: "RGCUBE",
                Logitech: "GR0006"
            } ], MODEL, [ TYPE, CONSOLE ] ], [ /\b(sm-[lr]\d\d[0156][fnuw]?s?|gear live)\b/i ], [ MODEL, [ VENDOR, SAMSUNG ], [ TYPE, WEARABLE ] ], [ /((pebble))app/i, /(asus|google|lg|oppo|xiaomi) ((pixel |zen)?watch[\w ]*)( bui|\))/i ], [ VENDOR, MODEL, [ TYPE, WEARABLE ] ], [ /(ow(?:19|20)?we?[1-3]{1,3})/i ], [ MODEL, [ VENDOR, OPPO ], [ TYPE, WEARABLE ] ], [ /(watch)(?: ?os[,\/]|\d,\d\/)[\d\.]+/i ], [ MODEL, [ VENDOR, APPLE ], [ TYPE, WEARABLE ] ], [ /(opwwe\d{3})/i ], [ MODEL, [ VENDOR, ONEPLUS ], [ TYPE, WEARABLE ] ], [ /(moto 360)/i ], [ MODEL, [ VENDOR, MOTOROLA ], [ TYPE, WEARABLE ] ], [ /(smartwatch 3)/i ], [ MODEL, [ VENDOR, SONY ], [ TYPE, WEARABLE ] ], [ /(g watch r)/i ], [ MODEL, [ VENDOR, LG ], [ TYPE, WEARABLE ] ], [ /droid.+; (wt63?0{2,3})\)/i ], [ MODEL, [ VENDOR, ZEBRA ], [ TYPE, WEARABLE ] ], [ /droid.+; (glass) \d/i ], [ MODEL, [ VENDOR, GOOGLE ], [ TYPE, XR ] ], [ /(pico) ([\w ]+) os\d/i ], [ VENDOR, MODEL, [ TYPE, XR ] ], [ /(quest( \d| pro)?s?).+vr/i ], [ MODEL, [ VENDOR, FACEBOOK ], [ TYPE, XR ] ], [ /mobile vr; rv.+firefox/i ], [ [ TYPE, XR ] ], [ /(tesla)(?: qtcarbrowser|\/[-\w\.]+)/i ], [ VENDOR, [ TYPE, EMBEDDED ] ], [ /(aeobc)\b/i ], [ MODEL, [ VENDOR, AMAZON ], [ TYPE, EMBEDDED ] ], [ /(homepod).+mac os/i ], [ MODEL, [ VENDOR, APPLE ], [ TYPE, EMBEDDED ] ], [ /windows iot/i ], [ [ TYPE, EMBEDDED ] ], [ /droid.+; ([\w- ]+) (4k|android|smart|google)[- ]?tv/i ], [ MODEL, [ TYPE, SMARTTV ] ], [ /\b((4k|android|smart|opera)[- ]?tv|tv; rv:|large screen[\w ]+safari)\b/i ], [ [ TYPE, SMARTTV ] ], [ /droid .+?; ([^;]+?)(?: bui|; wv\)|\) applew|; hmsc).+?(mobile|vr|\d) safari/i ], [ MODEL, [ TYPE, strMapper, {
                mobile: "Mobile",
                xr: "VR",
                "*": TABLET
            } ] ], [ /\b((tablet|tab)[;\/]|focus\/\d(?!.+mobile))/i ], [ [ TYPE, TABLET ] ], [ /(phone|mobile(?:[;\/]| [ \w\/\.]*safari)|pda(?=.+windows ce))/i ], [ [ TYPE, MOBILE ] ], [ /droid .+?; ([\w\. -]+)( bui|\))/i ], [ MODEL, [ VENDOR, "Generic" ] ] ],
            engine: [ [ /windows.+ edge\/([\w\.]+)/i ], [ VERSION, [ NAME, EDGE + "HTML" ] ], [ /(arkweb)\/([\w\.]+)/i ], [ NAME, VERSION ], [ /webkit\/537\.36.+chrome\/(?!27)([\w\.]+)/i ], [ VERSION, [ NAME, "Blink" ] ], [ /(presto)\/([\w\.]+)/i, /(webkit|trident|netfront|netsurf|amaya|lynx|w3m|goanna|servo)\/([\w\.]+)/i, /ekioh(flow)\/([\w\.]+)/i, /(khtml|tasman|links|dillo)[\/ ]\(?([\w\.]+)/i, /(icab)[\/ ]([23]\.[\d\.]+)/i, /\b(libweb)/i ], [ NAME, VERSION ], [ /ladybird\//i ], [ [ NAME, "LibWeb" ] ], [ /rv\:([\w\.]{1,9})\b.+(gecko)/i ], [ VERSION, NAME ] ],
            os: [ [ /(windows nt) (6\.[23]); arm/i ], [ [ NAME, /N/, "R" ], [ VERSION, strMapper, windowsVersionMap ] ], [ /(windows (?:phone|mobile|iot))(?: os)?[\/ ]?([\d\.]*( se)?)/i, /(windows)[\/ ](1[01]|2000|3\.1|7|8(\.1)?|9[58]|me|server 20\d\d( r2)?|vista|xp)/i ], [ NAME, VERSION ], [ /windows nt ?([\d\.\)]*)(?!.+xbox)/i, /\bwin(?=3| ?9|n)(?:nt| 9x )?([\d\.;]*)/i ], [ [ VERSION, /(;|\))/g, "", strMapper, windowsVersionMap ], [ NAME, WINDOWS ] ], [ /(windows ce)\/?([\d\.]*)/i ], [ NAME, VERSION ], [ /[adehimnop]{4,7}\b(?:.*os ([\w]+) like mac|; opera)/i, /(?:ios;fbsv|ios(?=.+ip(?:ad|hone)|.+apple ?tv)|ip(?:ad|hone)(?: |.+i(?:pad)?)os|apple ?tv.+ios)[\/ ]([\w\.]+)/i, /\btvos ?([\w\.]+)/i, /cfnetwork\/.+darwin/i ], [ [ VERSION, /_/g, "." ], [ NAME, "iOS" ] ], [ /(mac os x) ?([\w\. ]*)/i, /(macintosh|mac_powerpc\b)(?!.+(haiku|morphos))/i ], [ [ NAME, "macOS" ], [ VERSION, /_/g, "." ] ], [ /android ([\d\.]+).*crkey/i ], [ VERSION, [ NAME, CHROMECAST + " Android" ] ], [ /fuchsia.*crkey\/([\d\.]+)/i ], [ VERSION, [ NAME, CHROMECAST + " Fuchsia" ] ], [ /crkey\/([\d\.]+).*devicetype\/smartspeaker/i ], [ VERSION, [ NAME, CHROMECAST + " SmartSpeaker" ] ], [ /linux.*crkey\/([\d\.]+)/i ], [ VERSION, [ NAME, CHROMECAST + " Linux" ] ], [ /crkey\/([\d\.]+)/i ], [ VERSION, [ NAME, CHROMECAST ] ], [ /droid ([\w\.]+)\b.+(android[- ]x86)/i ], [ VERSION, NAME ], [ /(ubuntu) ([\w\.]+) like android/i ], [ [ NAME, /(.+)/, "$1 Touch" ], VERSION ], [ /(harmonyos)[\/ ]?([\d\.]*)/i, /(android|bada|blackberry|kaios|maemo|meego|openharmony|qnx|rim tablet os|sailfish|series40|symbian|tizen)\w*[-\/\.; ]?([\d\.]*)/i ], [ NAME, VERSION ], [ /\(bb(10);/i ], [ VERSION, [ NAME, BLACKBERRY ] ], [ /(?:symbian ?os|symbos|s60(?=;)|series ?60)[-\/ ]?([\w\.]*)/i ], [ VERSION, [ NAME, "Symbian" ] ], [ /mozilla\/[\d\.]+ \((?:mobile[;\w ]*|tablet|tv|[^\)]*(?:viera|lg(?:l25|-d300)|alcatel ?o.+|y300-f1)); rv:([\w\.]+)\).+gecko\//i ], [ VERSION, [ NAME, FIREFOX + " OS" ] ], [ /\b(?:hp)?wos(?:browser)?\/([\w\.]+)/i, /webos(?:[ \/]?|\.tv-20(?=2[2-9]))(\d[\d\.]*)/i ], [ VERSION, [ NAME, "webOS" ] ], [ /web0s;.+?(?:chr[o0]me|safari)\/(\d+)/i ], [ [ VERSION, strMapper, {
                25: "120",
                24: "108",
                23: "94",
                22: "87",
                6: "79",
                5: "68",
                4: "53",
                3: "38",
                2: "538",
                1: "537",
                "*": "TV"
            } ], [ NAME, "webOS" ] ], [ /watch(?: ?os[,\/ ]|\d,\d\/)([\d\.]+)/i ], [ VERSION, [ NAME, "watchOS" ] ], [ /cros [\w]+(?:\)| ([\w\.]+)\b)/i ], [ VERSION, [ NAME, "Chrome OS" ] ], [ /kepler ([\w\.]+); (aft|aeo)/i ], [ VERSION, [ NAME, "Vega OS" ] ], [ /(netrange)mmh/i, /(nettv)\/(\d+\.[\w\.]+)/i, /(nintendo|playstation) (\w+)/i, /(xbox); +xbox ([^\);]+)/i, /(pico) .+os([\w\.]+)/i, /\b(joli|palm)\b ?(?:os)?\/?([\w\.]*)/i, /linux.+(mint)[\/\(\) ]?([\w\.]*)/i, /(mageia|vectorlinux|fuchsia|arcaos|arch(?= ?linux))[;l ]([\d\.]*)/i, /([kxln]?ubuntu|debian|suse|opensuse|gentoo|slackware|fedora|mandriva|centos|pclinuxos|red ?hat|zenwalk|linpus|raspbian|plan 9|minix|risc os|contiki|deepin|manjaro|elementary os|sabayon|linspire|knoppix)(?: gnu[\/ ]linux)?(?: enterprise)?(?:[- ]linux)?(?:-gnu)?[-\/ ]?(?!chrom|package)([-\w\.]*)/i, /((?:open)?solaris)[-\/ ]?([\w\.]*)/i, /\b(aix)[; ]([1-9\.]{0,4})/i, /(hurd|linux|morphos)(?: (?:arm|x86|ppc)\w*| ?)([\w\.]*)/i, /(gnu) ?([\w\.]*)/i, /\b([-frentopcghs]{0,5}bsd|dragonfly)[\/ ]?(?!amd|[ix346]{1,2}86)([\w\.]*)/i, /(haiku) ?(r\d)?/i ], [ NAME, VERSION ], [ /(sunos) ?([\d\.]*)/i ], [ [ NAME, "Solaris" ], VERSION ], [ /\b(beos|os\/2|amigaos|openvms|hp-ux|serenityos)/i, /(unix) ?([\w\.]*)/i ], [ NAME, VERSION ] ]
        };
        var defaultProps = function() {
            var props = {
                init: {},
                isIgnore: {},
                isIgnoreRgx: {},
                toString: {}
            };
            setProps.call(props.init, [ [ BROWSER, [ NAME, VERSION, MAJOR, TYPE ] ], [ CPU, [ ARCHITECTURE ] ], [ DEVICE, [ TYPE, MODEL, VENDOR ] ], [ ENGINE, [ NAME, VERSION ] ], [ OS, [ NAME, VERSION ] ] ]);
            setProps.call(props.isIgnore, [ [ BROWSER, [ VERSION, MAJOR ] ], [ ENGINE, [ VERSION ] ], [ OS, [ VERSION ] ] ]);
            setProps.call(props.isIgnoreRgx, [ [ BROWSER, / ?browser$/i ], [ OS, / ?os$/i ] ]);
            setProps.call(props.toString, [ [ BROWSER, [ NAME, VERSION ] ], [ CPU, [ ARCHITECTURE ] ], [ DEVICE, [ VENDOR, MODEL ] ], [ ENGINE, [ NAME, VERSION ] ], [ OS, [ NAME, VERSION ] ] ]);
            return props;
        }();
        var createIData = function(item, itemType) {
            var init_props = defaultProps.init[itemType], is_ignoreProps = defaultProps.isIgnore[itemType] || 0, is_ignoreRgx = defaultProps.isIgnoreRgx[itemType] || 0, toString_props = defaultProps.toString[itemType] || 0;
            function IData() {
                setProps.call(this, init_props);
            }
            IData.prototype.getItem = function() {
                return item;
            };
            IData.prototype.withClientHints = function() {
                if (!NAVIGATOR_UADATA) {
                    return item.parseCH().get();
                }
                return NAVIGATOR_UADATA.getHighEntropyValues(CH_ALL_VALUES).then(function(res) {
                    return item.setCH(new UACHData(res, false)).parseCH().get();
                });
            };
            IData.prototype.withFeatureCheck = function() {
                return item.detectFeature().get();
            };
            if (itemType != RESULT) {
                IData.prototype.is = function(strToCheck) {
                    var is = false;
                    for (var i in this) {
                        if (this.hasOwnProperty(i) && !has(is_ignoreProps, i) && lowerize(is_ignoreRgx ? strip(is_ignoreRgx, this[i]) : this[i]) == lowerize(is_ignoreRgx ? strip(is_ignoreRgx, strToCheck) : strToCheck)) {
                            is = true;
                            if (strToCheck != TYPEOF.UNDEFINED) break;
                        } else if (strToCheck == TYPEOF.UNDEFINED && is) {
                            is = !is;
                            break;
                        }
                    }
                    return is;
                };
                IData.prototype.toString = function() {
                    var str = EMPTY;
                    for (var i in toString_props) {
                        if (typeof this[toString_props[i]] !== TYPEOF.UNDEFINED) {
                            str += (str ? " " : EMPTY) + this[toString_props[i]];
                        }
                    }
                    return str || TYPEOF.UNDEFINED;
                };
            }
            IData.prototype.then = function(cb) {
                var that = this;
                var IDataResolve = function() {
                    for (var prop in that) {
                        if (that.hasOwnProperty(prop)) {
                            this[prop] = that[prop];
                        }
                    }
                };
                IDataResolve.prototype = {
                    is: IData.prototype.is,
                    toString: IData.prototype.toString,
                    withClientHints: IData.prototype.withClientHints,
                    withFeatureCheck: IData.prototype.withFeatureCheck
                };
                var resolveData = new IDataResolve;
                cb(resolveData);
                return resolveData;
            };
            return new IData;
        };
        function UACHData(uach, isHttpUACH) {
            uach = uach || {};
            setProps.call(this, CH_ALL_VALUES);
            if (isHttpUACH) {
                setProps.call(this, [ [ BRANDS, itemListToArray(uach[CH]) ], [ FULLVERLIST, itemListToArray(uach[CH_FULL_VER_LIST]) ], [ MOBILE, /\?1/.test(uach[CH_MOBILE]) ], [ MODEL, stripQuotes(uach[CH_MODEL]) ], [ PLATFORM, stripQuotes(uach[CH_PLATFORM]) ], [ PLATFORMVER, stripQuotes(uach[CH_PLATFORM_VER]) ], [ ARCHITECTURE, stripQuotes(uach[CH_ARCH]) ], [ FORMFACTORS, itemListToArray(uach[CH_FORM_FACTORS]) ], [ BITNESS, stripQuotes(uach[CH_BITNESS]) ] ]);
            } else {
                for (var prop in uach) {
                    if (this.hasOwnProperty(prop) && typeof uach[prop] !== TYPEOF.UNDEFINED) this[prop] = uach[prop];
                }
            }
        }
        function UAItem(itemType, ua, rgxMap, uaCH) {
            setProps.call(this, [ [ "itemType", itemType ], [ "ua", ua ], [ "uaCH", uaCH ], [ "rgxMap", rgxMap ], [ "data", createIData(this, itemType) ] ]);
            return this;
        }
        UAItem.prototype.get = function(prop) {
            if (!prop) return this.data;
            return this.data.hasOwnProperty(prop) ? this.data[prop] : undefined;
        };
        UAItem.prototype.set = function(prop, val) {
            this.data[prop] = val;
            return this;
        };
        UAItem.prototype.setCH = function(ch) {
            this.uaCH = ch;
            return this;
        };
        UAItem.prototype.detectFeature = function() {
            if (NAVIGATOR && NAVIGATOR.userAgent == this.ua) {
                switch (this.itemType) {
                  case BROWSER:
                    if (NAVIGATOR.brave && typeof NAVIGATOR.brave.isBrave == TYPEOF.FUNCTION) {
                        this.set(NAME, "Brave");
                    }
                    break;

                  case DEVICE:
                    if (!this.get(TYPE) && NAVIGATOR_UADATA && NAVIGATOR_UADATA[MOBILE]) {
                        this.set(TYPE, MOBILE);
                    }
                    if (this.get(MODEL) == "Macintosh" && NAVIGATOR && typeof NAVIGATOR.standalone !== TYPEOF.UNDEFINED && NAVIGATOR.maxTouchPoints && NAVIGATOR.maxTouchPoints > 2) {
                        this.set(MODEL, "iPad").set(TYPE, TABLET);
                    }
                    break;

                  case OS:
                    if (!this.get(NAME) && NAVIGATOR_UADATA && NAVIGATOR_UADATA[PLATFORM]) {
                        this.set(NAME, NAVIGATOR_UADATA[PLATFORM]);
                    }
                    break;

                  case RESULT:
                    var data = this.data;
                    var detect = function(itemType) {
                        return data[itemType].getItem().detectFeature().get();
                    };
                    this.set(BROWSER, detect(BROWSER)).set(CPU, detect(CPU)).set(DEVICE, detect(DEVICE)).set(ENGINE, detect(ENGINE)).set(OS, detect(OS));
                }
            }
            return this;
        };
        UAItem.prototype.parseUA = function() {
            if (this.itemType != RESULT) {
                rgxMapper.call(this.data, this.ua, this.rgxMap);
            }
            switch (this.itemType) {
              case BROWSER:
                this.set(MAJOR, majorize(this.get(VERSION)));
                break;

              case OS:
                if (this.get(NAME) == "iOS" && this.get(VERSION) == "18.6") {
                    var realVersion = /\) Version\/([\d\.]+)/.exec(this.ua);
                    if (realVersion && parseInt(realVersion[1].substring(0, 2), 10) >= 26) {
                        this.set(VERSION, realVersion[1]);
                    }
                }
                break;
            }
            return this;
        };
        UAItem.prototype.parseCH = function() {
            var uaCH = this.uaCH, rgxMap = this.rgxMap;
            switch (this.itemType) {
              case BROWSER:
              case ENGINE:
                var brands = uaCH[FULLVERLIST] || uaCH[BRANDS], prevName;
                if (brands) {
                    for (var i = 0; i < brands.length; i++) {
                        var brandName = brands[i].brand || brands[i], brandVersion = brands[i].version;
                        if (this.itemType == BROWSER && !/not.a.brand/i.test(brandName) && (!prevName || /Chrom/.test(prevName) && brandName != CHROMIUM || prevName == EDGE && /WebView2/.test(brandName))) {
                            brandName = strMapper(brandName, browserHintsMap);
                            prevName = this.get(NAME);
                            if (!(prevName && !/Chrom/.test(prevName) && /Chrom/.test(brandName))) {
                                this.set(NAME, brandName).set(VERSION, brandVersion).set(MAJOR, majorize(brandVersion));
                            }
                            prevName = brandName;
                        }
                        if (this.itemType == ENGINE && brandName == CHROMIUM) {
                            this.set(VERSION, brandVersion);
                        }
                    }
                }
                break;

              case CPU:
                var archName = uaCH[ARCHITECTURE];
                if (archName) {
                    if (archName && uaCH[BITNESS] == "64") archName += "64";
                    rgxMapper.call(this.data, archName + ";", rgxMap);
                }
                break;

              case DEVICE:
                if (uaCH[MOBILE]) {
                    this.set(TYPE, MOBILE);
                }
                if (uaCH[MODEL]) {
                    this.set(MODEL, uaCH[MODEL]);
                    if (!this.get(TYPE) || !this.get(VENDOR)) {
                        var reParse = {};
                        rgxMapper.call(reParse, "droid 9; " + uaCH[MODEL] + ")", rgxMap);
                        if (!this.get(TYPE) && !!reParse.type) {
                            this.set(TYPE, reParse.type);
                        }
                        if (!this.get(VENDOR) && !!reParse.vendor) {
                            this.set(VENDOR, reParse.vendor);
                        }
                    }
                }
                if (uaCH[FORMFACTORS]) {
                    var ff;
                    if (typeof uaCH[FORMFACTORS] !== "string") {
                        var idx = 0;
                        while (!ff && idx < uaCH[FORMFACTORS].length) {
                            ff = strMapper(uaCH[FORMFACTORS][idx++], formFactorsMap);
                        }
                    } else {
                        ff = strMapper(uaCH[FORMFACTORS], formFactorsMap);
                    }
                    this.set(TYPE, ff);
                }
                break;

              case OS:
                var osName = uaCH[PLATFORM];
                if (osName) {
                    var osVersion = uaCH[PLATFORMVER];
                    if (osName == WINDOWS) osVersion = parseInt(majorize(osVersion), 10) >= 13 ? "11" : "10";
                    this.set(NAME, osName).set(VERSION, osVersion);
                }
                if (this.get(NAME) == WINDOWS && uaCH[MODEL] == "Xbox") {
                    this.set(NAME, "Xbox").set(VERSION, undefined);
                }
                break;

              case RESULT:
                var data = this.data;
                var parse = function(itemType) {
                    return data[itemType].getItem().setCH(uaCH).parseCH().get();
                };
                this.set(BROWSER, parse(BROWSER)).set(CPU, parse(CPU)).set(DEVICE, parse(DEVICE)).set(ENGINE, parse(ENGINE)).set(OS, parse(OS));
            }
            return this;
        };
        function UAParser(ua, extensions, headers) {
            if (typeof ua === TYPEOF.OBJECT) {
                if (isExtensions(ua, true)) {
                    if (typeof extensions === TYPEOF.OBJECT) {
                        headers = extensions;
                    }
                    extensions = ua;
                } else {
                    headers = ua;
                    extensions = undefined;
                }
                ua = undefined;
            } else if (typeof ua === TYPEOF.STRING && !isExtensions(extensions, true)) {
                headers = extensions;
                extensions = undefined;
            }
            if (headers) {
                if (typeof headers.append === TYPEOF.FUNCTION) {
                    var kv = {};
                    headers.forEach(function(v, k) {
                        kv[String(k).toLowerCase()] = v;
                    });
                    headers = kv;
                } else {
                    var normalized = {};
                    for (var header in headers) {
                        if (headers.hasOwnProperty(header)) {
                            normalized[String(header).toLowerCase()] = headers[header];
                        }
                    }
                    headers = normalized;
                }
            }
            if (!(this instanceof UAParser)) {
                return new UAParser(ua, extensions, headers).getResult();
            }
            var userAgent = typeof ua === TYPEOF.STRING ? ua : headers && headers[USER_AGENT] ? headers[USER_AGENT] : NAVIGATOR && NAVIGATOR.userAgent ? NAVIGATOR.userAgent : EMPTY, httpUACH = new UACHData(headers, true), regexMap = extensions ? extend(defaultRegexes, extensions) : defaultRegexes, createItemFunc = function(itemType) {
                if (itemType == RESULT) {
                    return function() {
                        return new UAItem(itemType, userAgent, regexMap, httpUACH).set("ua", userAgent).set(BROWSER, this.getBrowser()).set(CPU, this.getCPU()).set(DEVICE, this.getDevice()).set(ENGINE, this.getEngine()).set(OS, this.getOS()).get();
                    };
                } else {
                    return function() {
                        return new UAItem(itemType, userAgent, regexMap[itemType], httpUACH).parseUA().get();
                    };
                }
            };
            setProps.call(this, [ [ "getBrowser", createItemFunc(BROWSER) ], [ "getCPU", createItemFunc(CPU) ], [ "getDevice", createItemFunc(DEVICE) ], [ "getEngine", createItemFunc(ENGINE) ], [ "getOS", createItemFunc(OS) ], [ "getResult", createItemFunc(RESULT) ], [ "getUA", function() {
                return userAgent;
            } ], [ "setUA", function(ua) {
                if (isString(ua)) userAgent = trim(ua, UA_MAX_LENGTH);
                return this;
            } ] ]).setUA(userAgent);
            return this;
        }
        UAParser.VERSION = LIBVERSION;
        UAParser.BROWSER = enumerize([ NAME, VERSION, MAJOR, TYPE ]);
        UAParser.CPU = enumerize([ ARCHITECTURE ]);
        UAParser.DEVICE = enumerize([ MODEL, VENDOR, TYPE, CONSOLE, MOBILE, SMARTTV, TABLET, WEARABLE, EMBEDDED ]);
        UAParser.ENGINE = UAParser.OS = enumerize([ NAME, VERSION ]);
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
    __webpack_require__.n = module => {
        var getter = module && module.__esModule ? () => module["default"] : () => module;
        __webpack_require__.d(getter, {
            a: getter
        });
        return getter;
    };
})();

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
    var _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__("./node_modules/@babel/runtime/helpers/esm/typeof.js");
    var _EzauthConfig_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__("./src/ezauth/js/EzauthConfig.js");
    var _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__("./src/ezauth/utils/Logger.js");
    var ua_parser_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__("./node_modules/ua-parser-js/src/main/ua-parser.mjs");
    var Ezauth = {};
    (function(ezauth) {
        ezauth.isInitialized = false;
        ezauth.ezauthCallback = null;
        var windowMessageListener = null;
        ezauth.eventListener = function(event) {
            if (typeof window.EzauthConfig === "undefined") {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("EzauthConfig is not loaded yet in eventListener.");
                return;
            }
            if (event.data !== null && (0, _babel_runtime_helpers_typeof__WEBPACK_IMPORTED_MODULE_0__["default"])(event.data) === "object" && event.data.type === "shortCuts") {
                return;
            }
            if (!ezauth.isInitialized) {
                return;
            }
            var result;
            try {
                result = JSON.parse(event.data);
            } catch (e) {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("Failed to parse message data from child iframe:", e);
                return;
            }
            var ezauthContainer = document.getElementById(window.EzauthConfig.divId);
            if (!ezauthContainer) {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("Ezauth container not found during event handling.");
                return;
            }
            var iframeElement = ezauthContainer.querySelector("iframe");
            var loadingSection = ezauthContainer.querySelector("section");
            var loadingDiv = ezauthContainer.querySelector("div:last-child");
            if (result.errno === 0) {
                ezauth.close();
                var ezauthResponse = result;
                if (ezauth.ezauthCallback) {
                    ezauth.ezauthCallback(ezauthResponse);
                }
            } else if (result.errno === 10) {
                if (loadingSection) loadingSection.style.display = "none";
                if (loadingDiv) loadingDiv.style.display = "none";
                if (iframeElement) {
                    iframeElement.focus();
                    iframeElement.style.display = "block";
                }
            } else if (result.errno === 302) {
                ezauth.close();
                if (ezauth.ezauthCallback) {
                    ezauth.ezauthCallback({
                        code: "-100",
                        error: result.errstr || "User cancelled the authentication process."
                    });
                }
            } else if (result.errno === 309) {
                ezauth.close();
                if (ezauth.ezauthCallback) {
                    ezauth.ezauthCallback({
                        code: "-100",
                        error: result.errstr || "User cancelled cloud window shot down."
                    });
                }
            } else {
                ezauth.close();
                if (ezauth.ezauthCallback) {
                    ezauth.ezauthCallback({
                        code: "-1",
                        error: result.errstr || "An unknown error occurred during authentication."
                    });
                }
            }
        };
        ezauth.init = function(basicInfo) {
            if (typeof window.EzauthConfig === "undefined") {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("EzauthConfig is not loaded yet in init.");
                return;
            }
            if (ezauth.isInitialized === true) return;
            if (windowMessageListener) {
                window.removeEventListener("message", windowMessageListener);
            }
            windowMessageListener = ezauth.eventListener;
            window.addEventListener("message", windowMessageListener);
            var ezauthDiv = document.getElementById(window.EzauthConfig.divId);
            var uiChoice = basicInfo.uiChoice;
            var isPC = uiChoice === "pc";
            var childHtml = isPC ? window.EzauthConfig.childHtmlPath : window.EzauthConfig.childHtmlMobilePath;
            window.EzauthConfig.isPC = isPC;
            if (ezauthDiv === null) {
                ezauthDiv = document.createElement("div");
                ezauthDiv.setAttribute("id", window.EzauthConfig.divId);
                ezauthDiv.style.display = "none";
                var strEzauthDiv = '\n                <iframe src="'.concat(window.EzauthConfig.ezauthRootPath).concat(childHtml, '" scrolling="no" width="100%" height="100%" frameborder="0" translate="yes"\n                    style="display: none; position: fixed; z-index: ').concat(window.EzauthConfig.layerZIndex, '; top: 0px; left: 0px; width:100%; height:100%;"></iframe>\n                <section style="margin: 0; padding: 0; position: fixed; z-index: ').concat(1 + window.EzauthConfig.layerZIndex, '; top: 0; left: 0; width: 100%; height: 100%; background-color: #000; cursor: wait; opacity: 0.6; display: none;">\n                </section>\n                <div style="margin: 0; padding: 7px 0; position: fixed; z-index: ').concat(2 + window.EzauthConfig.layerZIndex, '; top: 50%; left: 50%; transform: translate(-50%, -50%); background-color: transparent; border-radius: 5px; width: fit-content; min-width: 150px; cursor: wait; display: none;">\n                    <div style="text-align: center;" >\n                        <style>\n                            .bea-loading-img{\n                                width: 4rem;\n                                height: 4rem;\n\n                                animation-name: bea-spin; /* 위에서 정의한 @keyframes 이름 */\n                                animation-duration: 3s; /* 한 바퀴 도는 데 걸리는 시간 (3초) */\n                                animation-timing-function: linear; /* 일정한 속도로 회전 */\n                                animation-iteration-count: infinite; /* 무한 반복 */\n                            }\n                            @keyframes bea-spin {\n                                from {\n                                    transform: rotate(0deg); /* 0도에서 시작 */\n                                }\n                                to {\n                                    transform: rotate(360deg); /* 360도(한 바퀴)까지 회전 */\n                                }\n                            }\n                        </style>\n                        <img class="bea-loading-img" src="').concat(window.EzauthConfig.ezauthRootPath, '/assets/img/ico_loading.svg" style="margin: 0 auto; padding: 0;" title="로딩 중" alt="로딩 중">\n                    </div>\n                </div>\n            ');
                ezauthDiv.innerHTML = strEzauthDiv;
                var body = document.getElementsByTagName("body")[0];
                if (body) {
                    body.appendChild(ezauthDiv);
                } else {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("Body element not found. Cannot append EzauthDiv.");
                    return;
                }
            } else {
                var iframe = ezauthDiv.querySelector("iframe");
                if (iframe) {
                    iframe.src = window.EzauthConfig.ezauthRootPath + childHtml;
                }
            }
            ezauth.isInitialized = true;
        };
        ezauth.close = function() {
            if (typeof window.EzauthConfig === "undefined" || !window.EzauthConfig.divId) {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("EzauthConfig is not loaded. Cannot close.");
                return;
            }
            var ezauthContainer = document.getElementById(window.EzauthConfig.divId);
            if (ezauthContainer === null) {
                return;
            }
            var iframeElement = ezauthContainer.querySelector("iframe");
            var loadingSection = ezauthContainer.querySelector("section");
            var loadingDiv = ezauthContainer.querySelector("div:last-child");
            if (iframeElement) iframeElement.style.display = "none";
            if (loadingSection) loadingSection.style.display = "none";
            if (loadingDiv) loadingDiv.style.display = "none";
            ezauthContainer.remove();
            if (windowMessageListener) {
                window.removeEventListener("message", windowMessageListener);
                windowMessageListener = null;
            }
            ezauth.isInitialized = false;
        };
        ezauth.makeEzauth = function(basicInfo, ezauthCallback) {
            var _waitForConfig = function waitForConfig(callback) {
                if (typeof window.EzauthConfig !== "undefined" && window.EzauthConfig.divId) {
                    callback();
                } else {
                    setTimeout(function() {
                        return _waitForConfig(callback);
                    }, 50);
                }
            };
            _waitForConfig(function() {
                if (basicInfo === undefined || basicInfo === null) {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("basicInfo parameter is missing or null.");
                    if (ezauthCallback) {
                        ezauthCallback({
                            code: "-1",
                            error: "basicInfo parameter is missing or null."
                        });
                    }
                    return;
                }
                if (!basicInfo.configPath) {
                    if (window.EzauthConfig.defaultConfigPath) {
                        basicInfo.configPath = window.EzauthConfig.defaultConfigPath;
                    } else {
                        basicInfo.configPath = "site/portal/authBiz.json";
                    }
                }
                if (basicInfo.sender === undefined || basicInfo.sender !== "MLHub") {
                    basicInfo.sender = "MLHub";
                }
                basicInfo.ezauthRootPath = window.EzauthConfig.ezauthRootPath;
                var uiChoice = (basicInfo.uiChoice || "").toLowerCase();
                if (uiChoice !== "pc" && uiChoice !== "mobile") {
                    basicInfo.uiChoice = ezauth.getDeviceType();
                }
                ezauth.init(basicInfo);
                var ezauthContainer = document.getElementById(window.EzauthConfig.divId);
                if (ezauthContainer) {
                    ezauthContainer.style.display = "block";
                    var iframeElement = ezauthContainer.querySelector("iframe");
                    var loadingSection = ezauthContainer.querySelector("section");
                    var loadingDiv = ezauthContainer.querySelector("div:last-child");
                    if (loadingSection) loadingSection.style.display = "block";
                    if (loadingDiv) loadingDiv.style.display = "block";
                    if (iframeElement) iframeElement.style.display = "none";
                } else {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("Ezauth container not found after init. Cannot display UI.");
                    if (ezauthCallback) {
                        ezauthCallback({
                            code: "-1",
                            error: "Initialization failed: Container not found."
                        });
                    }
                    return;
                }
                ezauth.isInitialized = true;
                ezauth.sendMessageToChild(basicInfo, null);
                ezauth.ezauthCallback = ezauthCallback;
            });
        };
        ezauth.makeEzauthSimple = function(options, callback) {
            var _options$siteInfo;
            if (typeof options === "function") {
                callback = options;
                options = {};
            }
            options = options || {};
            var defaults = {
                serviceType: "auth"
            };
            if ((_options$siteInfo = options.siteInfo) !== null && _options$siteInfo !== void 0 && _options$siteInfo.siteId) {
                defaults.configPath = "site/" + options.siteInfo.siteId + "/authBiz.json";
            }
            var basicInfo = Object.assign({}, defaults, options);
            ezauth.makeEzauth(basicInfo, callback);
        };
        ezauth.sendMessageToChild = function(data, _origin) {
            var iframeElement = document.querySelector("#" + window.EzauthConfig.divId + " > iframe");
            if (!iframeElement || !iframeElement.contentWindow) {
                _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error("Child iframe not found or not ready to receive messages.");
                return;
            }
            var uiChoice = (data.uiChoice || "").toLowerCase();
            if (uiChoice !== "mobile" && uiChoice !== "pc") {
                uiChoice = ezauth.getDeviceType();
            }
            var childGlobalName = uiChoice === "mobile" ? "EzAuthMAct" : "EzAuthPAct";
            var sendMessageAndRegister = function sendMessageAndRegister() {
                var childObject = iframeElement.contentWindow[childGlobalName];
                if (childObject && typeof childObject.registEvent === "function") {
                    childObject.registEvent();
                    iframeElement.contentWindow.postMessage(data, _origin || "*");
                } else {
                    _utils_Logger_js__WEBPACK_IMPORTED_MODULE_2__["default"].error('"'.concat(childGlobalName, '" or its registEvent method not found in iframe contentWindow.'));
                }
            };
            var childObject = iframeElement.contentWindow[childGlobalName];
            if (childObject && typeof childObject.registEvent === "function") {
                sendMessageAndRegister();
            } else {
                iframeElement.onload = function() {
                    sendMessageAndRegister();
                    iframeElement.onload = null;
                };
            }
        };
        ezauth.getDeviceType = function() {
            var isModernIpad = /Macintosh/i.test(navigator.userAgent) && navigator.maxTouchPoints > 1;
            if (isModernIpad) return "mobile";
            var parser = new ua_parser_js__WEBPACK_IMPORTED_MODULE_3__.UAParser;
            var deviceType = parser.getDevice().type;
            if (deviceType === "mobile" || deviceType === "tablet") return "mobile";
            return "pc";
        };
    })(Ezauth);
    const __WEBPACK_DEFAULT_EXPORT__ = Ezauth;
})();

window.EzAuth = __webpack_exports__["default"];