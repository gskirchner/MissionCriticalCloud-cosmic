(function ($, cloudStack) {
    var elems = {
        pluginItem: function (args) {
            var id = args.id;
            var title = args.title;
            var desc = args.desc;
            var iconURL = args.iconURL;
            var $pluginItem = $('<li>').addClass('plugin-item').addClass(id);
            var $title = $('<span>').addClass('title').html(title);
            var $desc = $('<span>').addClass('desc').html(desc);
            var $icon = $('<span>').addClass('icon').append(
                $('<img>').attr({
                    src: iconURL
                })
            );

            $pluginItem.append(
                $icon, $title, $desc
            );

            return $pluginItem;
        },
        pluginListing: function (args) {
            var plugins = args.plugins;
            var $plugins = $('<ul>');
            var $pluginsListing = $('<div>').addClass('plugins-listing');

            $(plugins).each(function () {
                var plugin = this;
                var $plugin = elems.pluginItem({
                    id: plugin.id,
                    title: plugin.title,
                    desc: plugin.desc,
                    iconURL: 'plugins/' + plugin.id + '/icon.png'
                });
                var $browser = $('#browser .container');

                $plugin.click(function () {
                    var $mainSection = $('#navigation ul li').filter('.' + plugin.id);

                    if ($mainSection.size()) {
                        $mainSection.click();

                        return;
                    }

                    $browser.cloudBrowser('addPanel', {
                        title: plugin.title,
                        $parent: $('.panel:first'),
                        complete: function ($panel) {
                            $panel.detailView({
                                name: 'Plugin details',
                                tabs: {
                                    details: {
                                        title: 'label.plugin.details',
                                        fields: [{
                                            name: {
                                                label: 'label.name'
                                            }
                                        }, {
                                            desc: {
                                                label: 'label.description'
                                            },
                                            externalLink: {
                                                isExternalLink: true,
                                                label: 'label.external.link'
                                            }
                                        }, {
                                            authorName: {
                                                label: 'label.author.name'
                                            },
                                            authorEmail: {
                                                label: 'label.author.email'
                                            },
                                            id: {
                                                label: 'label.id'
                                            }
                                        }],
                                        dataProvider: function (args) {
                                            args.response.success({
                                                data: plugin
                                            });
                                        }
                                    }
                                }
                            });
                        }
                    });
                });

                $plugin.appendTo($plugins);
            });

            $pluginsListing.append($plugins);

            return $pluginsListing;
        }
    };

    cloudStack.uiCustom.pluginListing = function () {
        var plugins = cloudStack.plugins;

        return elems.pluginListing({
            plugins: $(plugins).map(function (index, pluginID) {
                var plugin = cloudStack.plugins[pluginID].config;

                return $.extend(plugin, {
                    id: pluginID
                });
            })
        });
    };
}(jQuery, cloudStack));