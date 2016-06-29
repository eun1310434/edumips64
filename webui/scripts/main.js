"use strict";angular.module("edmApp",["ngMaterial","ngAnimate","ui.router","pascalprecht.translate","ui.ace"]),angular.module("edmApp").config(["$mdThemingProvider","$locationProvider","$urlRouterProvider","$stateProvider","$translateProvider",function(e,t,l,n,o){o.useStaticFilesLoader({prefix:"locales/",suffix:".json"}),o.useSanitizeValueStrategy("escape"),o.determinePreferredLanguage(),o.fallbackLanguage("en-EN"),l.otherwise("/"),t.html5Mode({enabled:!0,requireBase:!0}),n.state("app",{controllerAs:"app",url:"/",controller:"AppController",templateUrl:"views/app.html"})}]),angular.module("edmApp").controller("AppController",["$scope","$log","$mdSidenav","$mdMedia","$mdDialog",function(e,t,l,n,o){var r=this;r.locs=0,r.customFullscreen=n("xs")||n("sm"),r.toggleMenu=function(){l("left").toggle()},r.editorChanged=function(e){r.locs=e[1].env.document.getLength()},r.openSettingsDialog=function(e){var l=(n("sm")||n("xs"))&&r.customFullscreen;o.show({controller:"SettingsDialogController",controllerAs:"vm",templateUrl:"views/settings-dialog.html",parent:angular.element(document.body),targetEvent:e,clickOutsideToClose:!0,fullscreen:l}).then(function(e){t.log("Settings dialog resolved",e)},function(){t.log("Settings dialog rejected")})},e.$watch(function(){return n("xs")||n("sm")},function(t){e.customFullscreen=t===!0})}]),angular.module("edmApp").controller("SettingsDialogController",["$mdDialog",function(e){var t=this;t.availableLanguages={"en-EN":"English","it-IT":"Italiano"},t.ok=function(){e.hide()},t.cancel=function(){e.cancel()}}]);