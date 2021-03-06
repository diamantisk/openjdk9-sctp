/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

var noResult = {l: "No results found"};
var category = "category";
var catModules = "Modules";
var catPackages = "Packages";
var catTypes = "Types";
var catMembers = "Members";
var catSearchTags = "SearchTags";
function getName(name) {
    var anchor = "";
    var ch = '';
    for (i = 0; i < name.length; i++) {
        ch = name.charAt(i);
        switch (ch) {
            case '(':
            case ')':
            case '<':
            case '>':
            case ',':
                anchor += "-";
                break;
            case ' ':
            case '[':
                break;
            case ']':
                anchor += ":A";
                break;
            case '$':
                if (i == 0)
                    anchor += "Z:Z";
                anchor += ":D";
                break;
            case '_':
                if (i == 0)
                    anchor += "Z:Z";
                anchor += ch;
                break;
            default:
                anchor += ch;
        }
    }
    return anchor;
}
var watermark = 'Search';
$(function() {
    $("#search").prop("disabled", false);
    $("#reset").prop("disabled", false);
    $("#search").val(watermark).addClass('watermark');
    $("#search").blur(function(){
        if ($(this).val().length == 0) {
            $(this).val(watermark).addClass('watermark');
        }
    });
    $("#search").keydown(function(){
       if ($(this).val() == watermark) {
            $(this).val('').removeClass('watermark');
        }
    });
    $("#reset").click(function(){
       $("#search").val('');
       $("#search").focus();
    });
    $("#search").focus();
    $("#search")[0].setSelectionRange(0,0);
});
$.widget("custom.catcomplete", $.ui.autocomplete, {
    _create: function() {
        this._super();
        this.widget().menu("option", "items", "> :not(.ui-autocomplete-category)");
    },
    _renderMenu: function(ul, items) {
        var rMenu = this,
                currentCategory = "";
        $.each(items, function(index, item) {
            var li;
            if (item.l !== noResult.l && item.category !== currentCategory) {
                ul.append("<li class=\"ui-autocomplete-category\">" + item.category + "</li>");
                currentCategory = item.category;
            }
            li = rMenu._renderItemData(ul, item);
            if (item.category) {
                li.attr("aria-label", item.category + " : " + item.l);
                li.attr("class", "resultItem");
            } else {
                li.attr("aria-label", item.l);
                li.attr("class", "resultItem");
            }
        });
    },
    _renderItem: function(ul, item) {
        var result = this.element.val();
        var regexp = new RegExp($.ui.autocomplete.escapeRegex(result), "i");
        highlight = "<span class=\"resultHighlight\">$&</span>";
        var label = "";
        if (item.category === catModules) {
            label = item.l.replace(regexp, highlight);
        } else if (item.category === catPackages) {
            label = (item.m)
                    ? (item.m + "/" + item.l).replace(regexp, highlight)
                    : item.l.replace(regexp, highlight);
        } else if (item.category === catTypes) {
            label += (item.p + "." + item.l).replace(regexp, highlight);
        } else if (item.category === catMembers) {
            label += item.p + "." + (item.c + "." + item.l).replace(regexp, highlight);
        } else if (item.category === catSearchTags) {
            label = item.l.replace(regexp, highlight);
        } else {
            label = item.l;
        }
        $li = $("<li/>").appendTo(ul);
        if (item.category === catSearchTags) {
            if (item.d) {
                $("<a/>").attr("href", "#")
                        .html(label + "<span class=\"searchTagHolderResult\"> (" + item.h + ")</span><br><span class=\"searchTagDescResult\">"
                                + item.d + "</span><br>")
                        .appendTo($li);
            } else {
                $("<a/>").attr("href", "#")
                        .html(label + "<span class=\"searchTagHolderResult\"> (" + item.h + ")</span>")
                        .appendTo($li);
            }
        } else {
            $("<a/>").attr("href", "#")
                    .html(label)
                    .appendTo($li);
        }
        return $li;
    }
});
$(function() {
    $("#search").catcomplete({
        minLength: 1,
        delay: 100,
        source: function(request, response) {
            var result = new Array();
            var presult = new Array();
            var tresult = new Array();
            var mresult = new Array();
            var tgresult = new Array();
            var displayCount = 0;
            var exactMatcher = new RegExp("^" + $.ui.autocomplete.escapeRegex(request.term) + "$", "i");
            var secondaryMatcher = new RegExp($.ui.autocomplete.escapeRegex(request.term), "i");
            if (moduleSearchIndex) {
                var mdleCount = 0;
                $.each(moduleSearchIndex, function(index, item) {
                    item[category] = catModules;
                    if (exactMatcher.test(item.l)) {
                        result.unshift(item);
                        mdleCount++;
                    } else if (secondaryMatcher.test(item.l)) {
                        result.push(item);
                    }
                });
                displayCount = mdleCount;
            }
            if (packageSearchIndex) {
                var pCount = 0;
                var pkg = "";
                $.each(packageSearchIndex, function(index, item) {
                    item[category] = catPackages;
                    pkg = (item.m)
                            ? (item.m + "/" + item.l)
                            : item.l;
                    if (exactMatcher.test(item.l)) {
                        presult.unshift(item);
                        pCount++;
                    } else if (secondaryMatcher.test(pkg)) {
                        presult.push(item);
                    }
                });
                result = result.concat(presult);
                displayCount = (pCount > displayCount) ? pCount : displayCount;
            }
            if (typeSearchIndex) {
                var tCount = 0;
                $.each(typeSearchIndex, function(index, item) {
                    item[category] = catTypes;
                    if (exactMatcher.test(item.l)) {
                        tresult.unshift(item);
                        tCount++;
                    } else if (secondaryMatcher.test(item.p + "." + item.l)) {
                        tresult.push(item);
                    }
                });
                result = result.concat(tresult);
                displayCount = (tCount > displayCount) ? tCount : displayCount;
            }
            if (memberSearchIndex) {
                var mCount = 0;
                $.each(memberSearchIndex, function(index, item) {
                    item[category] = catMembers;
                    if (exactMatcher.test(item.l)) {
                        mresult.unshift(item);
                        mCount++;
                    } else if (secondaryMatcher.test(item.c + "." + item.l)) {
                        mresult.push(item);
                    }
                });
                result = result.concat(mresult);
                displayCount = (mCount > displayCount) ? mCount : displayCount;
            }
            if (tagSearchIndex) {
                var tgCount = 0;
                $.each(tagSearchIndex, function(index, item) {
                    item[category] = catSearchTags;
                    if (exactMatcher.test(item.l)) {
                        tgresult.unshift(item);
                        tgCount++;
                    } else if (secondaryMatcher.test(item.l)) {
                        tgresult.push(item);
                    }
                });
                result = result.concat(tgresult);
                displayCount = (tgCount > displayCount) ? tgCount : displayCount;
            }
            displayCount = (displayCount > 500) ? displayCount : 500;
            var counter = function() {
                var count = {Modules: 0, Packages: 0, Types: 0, Members: 0, SearchTags: 0};
                var f = function(item) {
                    count[item.category] += 1;
                    return (count[item.category] <= displayCount);
                };
                return f;
            }();
            response(result.filter(counter));
        },
        response: function(event, ui) {
            if (!ui.content.length) {
                ui.content.push(noResult);
            } else {
                $("#search").empty();
            }
        },
        autoFocus: true,
        position: {
            collision: "flip"
        },
        select: function(event, ui) {
            if (ui.item.l !== noResult.l) {
                var url = "";
                if (ui.item.category === catModules) {
                    url = ui.item.l + "-summary.html";
                } else if (ui.item.category === catPackages) {
                    url = ui.item.l.replace(/\./g, '/') + "/package-summary.html";
                } else if (ui.item.category === catTypes) {
                    if (ui.item.p === "<Unnamed>") {
                        url = ui.item.l + ".html";
                    } else {
                        url = ui.item.p.replace(/\./g, '/') + "/" + ui.item.l + ".html";
                    }
                } else if (ui.item.category === catMembers) {
                    if (ui.item.p === "<Unnamed>") {
                        url = ui.item.c + ".html" + "#";
                    } else {
                        url = ui.item.p.replace(/\./g, '/') + "/" + ui.item.c + ".html" + "#";
                    }
                    if (ui.item.url) {
                        url += ui.item.url;
                    } else {
                        url += getName(ui.item.l);
                    }
                } else if (ui.item.category === catSearchTags) {
                    url += ui.item.u;
                }
                if (top !== window) {
                    parent.classFrame.location = pathtoroot + url;
                } else {
                    window.location.href = pathtoroot + url;
                }
            }
        }
    });
});
