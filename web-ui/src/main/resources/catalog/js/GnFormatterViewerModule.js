(function() {
  goog.provide('gn_formatter_viewer');











  goog.require('gn');
  goog.require('gn_alert');
  goog.require('gn_catalog_service');
  goog.require('gn_formatter_lib');
  goog.require('gn_popup');
  goog.require('gn_search_geocat_mdactionmenu');
  goog.require('gn_utility_directive');
  goog.require('gn_search_default_directive');
  goog.require('gn_mdactions');








  var module = angular.module('gn_formatter_viewer',
      ['ngRoute', 'gn', 'gn_utility_directive', 'gn_catalog_service',
        'gn_search_default_directive',
        'gn_popup', 'gn_search_geocat_mdactionmenu', 'gn_mdactions', 'gn_alert']);

  // Define the translation files to load
  module.config(['$LOCALES', function($LOCALES) {
    $LOCALES.push('search');
  }]);

  module.controller('GnFormatterViewer',
      ['$scope', '$http', '$sce', '$routeParams', 'Metadata',
       function($scope, $http, $sce, $routeParams, Metadata) {
        /* $scope.md = {
           'geonet:info': {}
         };*/
         $scope.metadata = '';
         $scope.loading = true;

         var formatter = $routeParams.formatter;
         var mdId = $routeParams.mdId;

         $http.get('md.format.xml?xsl=' + formatter + '&id=' + mdId).
         success(function(data) {
           $scope.loading = undefined;
           $scope.metadata = $sce.trustAsHtml(data);
         }).error(function(data) {
           $scope.loading = undefined;
           $scope.metadata = $sce.trustAsHtml(data);
         });
         var indexField = isNaN(mdId) ? '_uuid' : '_id';
         /*$http.get('qi?_content_type=json&fast=index&' + indexField + '=' +
         mdId).success(function(data) {
           $scope.md = new Metadata(data.metadata);
         });*/

       }]);
  module.config(['$routeProvider', function($routeProvider) {
    var tpls = '../../catalog/templates/';

    $routeProvider.when('/:formatter/:mdId', { templateUrl: tpls +
          '/formatter-viewer.html', controller: 'GnFormatterViewer'});
  }]);
})();
