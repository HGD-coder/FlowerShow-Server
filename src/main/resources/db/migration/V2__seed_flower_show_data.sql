insert into users (id, nickname, avatar_url, bio, location, source, source_user_id) values
('u001', 'Flower Lab', 'https://picsum.photos/200/200?random=1', 'Flower arrangement tutorials and home styling ideas', 'Shanghai', 'crawler', 'sec-flower-lab'),
('u002', 'Urban Blooms', 'https://picsum.photos/200/200?random=2', 'Balcony gardening and compact city gardens', 'Hangzhou', 'crawler', 'sec-urban-blooms'),
('u003', 'Bloom Care', 'https://picsum.photos/200/200?random=3', 'Cut flower and potted plant care notes', 'Suzhou', 'crawler', 'sec-bloom-care'),
('u004', 'Travel Petals', 'https://picsum.photos/200/200?random=4', 'Parks, flower markets, and short trips', 'Dali', 'crawler', 'sec-travel-petals'),
('u005', 'Calm Desk', 'https://picsum.photos/200/200?random=5', 'Desk setups, plants, and calm lifestyle notes', 'Chengdu', 'crawler', 'sec-calm-desk'),
('seed_u001', 'Balcony Lin', 'https://picsum.photos/200/200?random=101', 'Small-space balcony gardening fan', 'Hangzhou', 'seed', null),
('seed_u002', 'Market Walker', 'https://picsum.photos/200/200?random=102', 'Weekend flower market regular', 'Shanghai', 'seed', null),
('seed_u003', 'Iced Americano', 'https://picsum.photos/200/200?random=103', 'Home styling and desk setup notes', 'Suzhou', 'seed', null),
('seed_u004', 'Rose Care Notes', 'https://picsum.photos/200/200?random=104', 'Cut flower and rose care records', 'Chengdu', 'seed', null),
('seed_u005', 'Weekend Walks', 'https://picsum.photos/200/200?random=105', 'Parks, exhibits, and short trips', 'Nanjing', 'seed', null);

insert into content_items (id, type, title, description, author_user_id, cover_url, source_url, status, visibility, publish_time) values
('v001', 'video', 'Ten quick flower arrangement ideas', 'Quick flower arrangement ideas for home tables.', 'u001', 'https://picsum.photos/720/1280?random=11', 'https://example.com/videos/v001', 'published', 'public', 1714550400),
('v002', 'video', 'Balcony garden refresh in one afternoon', 'A small balcony garden refresh with layered plants.', 'u002', 'https://picsum.photos/720/1280?random=12', 'https://example.com/videos/v002', 'published', 'public', 1714636800),
('v003', 'video', 'How to keep roses fresh longer', 'Rose care tips for cut flowers.', 'u003', 'https://picsum.photos/720/1280?random=13', 'https://example.com/videos/v003', 'published', 'public', 1714723200),
('v004', 'video', 'A slow walk through a spring park', 'A peaceful spring route through a flower park.', 'u004', 'https://picsum.photos/720/1280?random=14', 'https://example.com/videos/v004', 'published', 'public', 1714809600),
('v005', 'video', 'Minimal desk setup with plants', 'A calm plant desk setup for small workspaces.', 'u005', 'https://picsum.photos/720/1280?random=15', 'https://example.com/videos/v005', 'published', 'public', 1714896000),
('img001', 'image', 'Spring garden color study', 'A garden color palette for spring.', 'u001', 'https://picsum.photos/720/900?random=30', null, 'published', 'public', 1714982400),
('img002', 'image', 'City balcony planting ideas', 'Compact planting ideas for city balconies.', 'u002', 'https://picsum.photos/720/900?random=31', null, 'published', 'public', 1715068800),
('alb001', 'album', 'Weekend flower market walk', 'A short album from the weekend flower market.', 'u004', 'https://picsum.photos/200/200?random=40', null, 'published', 'public', 1715155200),
('alb002', 'album', 'Tiny greenhouse inspiration', 'Small greenhouse details and plant combinations.', 'u003', 'https://picsum.photos/200/200?random=50', null, 'published', 'public', 1715241600);

insert into media_assets (content_id, kind, url, quality, sort_order) values
('v001', 'video', 'https://media.w3.org/2010/05/sintel/trailer.mp4', '360p', 0),
('v001', 'video', 'https://media.w3.org/2010/05/sintel/trailer.mp4', '720p', 1),
('v002', 'video', 'https://media.w3.org/2010/05/bunny/trailer.mp4', '360p', 0),
('v002', 'video', 'https://media.w3.org/2010/05/bunny/trailer.mp4', '720p', 1),
('v003', 'video', 'https://media.w3.org/2010/05/video/movie_300.mp4', '360p', 0),
('v003', 'video', 'https://media.w3.org/2010/05/video/movie_300.mp4', '720p', 1),
('v004', 'video', 'https://media.w3.org/2010/05/bunny/trailer.mp4', '360p', 0),
('v004', 'video', 'https://media.w3.org/2010/05/bunny/trailer.mp4', '720p', 1),
('v005', 'video', 'https://media.w3.org/2010/05/sintel/trailer.mp4', '360p', 0),
('v005', 'video', 'https://media.w3.org/2010/05/sintel/trailer.mp4', '720p', 1),
('img001', 'image', 'https://picsum.photos/720/900?random=30', null, 0),
('img002', 'image', 'https://picsum.photos/720/900?random=31', null, 0),
('alb001', 'image', 'https://picsum.photos/720/1280?random=41', null, 0),
('alb001', 'image', 'https://picsum.photos/720/1280?random=42', null, 1),
('alb001', 'image', 'https://picsum.photos/720/1280?random=43', null, 2),
('alb002', 'image', 'https://picsum.photos/720/1280?random=51', null, 0),
('alb002', 'image', 'https://picsum.photos/720/1280?random=52', null, 1),
('alb002', 'image', 'https://picsum.photos/720/1280?random=53', null, 2),
('alb002', 'image', 'https://picsum.photos/720/1280?random=54', null, 3);

insert into content_tags (content_id, tag, sort_order) values
('v001', 'flowers', 0), ('v001', 'arrangement', 1), ('v001', 'home', 2),
('v002', 'balcony', 0), ('v002', 'garden', 1), ('v002', 'plants', 2),
('v003', 'rose', 0), ('v003', 'care', 1), ('v003', 'tutorial', 2),
('v004', 'travel', 0), ('v004', 'park', 1), ('v004', 'spring', 2),
('v005', 'desk', 0), ('v005', 'plants', 1), ('v005', 'lifestyle', 2),
('img001', 'garden', 0), ('img001', 'color', 1), ('img001', 'spring', 2),
('img002', 'balcony', 0), ('img002', 'planting', 1),
('alb001', 'flowers', 0), ('alb001', 'market', 1), ('alb001', 'travel', 2),
('alb002', 'greenhouse', 0), ('alb002', 'design', 1), ('alb002', 'plants', 2);

insert into content_recommend_words (content_id, word, sort_order) values
('v001', 'small vase', 0), ('v001', 'fresh flowers', 1), ('v001', 'home decor', 2),
('v002', 'balcony plants', 0), ('v002', 'garden refresh', 1), ('v002', 'plant shelf', 2),
('v003', 'rose care', 0), ('v003', 'cut flowers', 1), ('v003', 'flower food', 2),
('v004', 'spring walk', 0), ('v004', 'park route', 1), ('v004', 'weekend travel', 2),
('v005', 'desk plant', 0), ('v005', 'minimal setup', 1), ('v005', 'plant decor', 2),
('alb001', 'fresh bouquet', 0), ('alb001', 'market route', 1), ('alb001', 'seasonal flowers', 2),
('alb002', 'small garden', 0), ('alb002', 'greenhouse layout', 1), ('alb002', 'plant care', 2);

insert into content_stats (content_id, like_count, comment_count, favorite_count, share_count, view_count) values
('v001', 125000, 4, 3400, 12000, 820000),
('v002', 78000, 4, 1800, 9600, 420000),
('v003', 67000, 3, 3200, 15000, 360000),
('v004', 112000, 0, 6700, 23000, 640000),
('v005', 234000, 0, 12000, 45000, 1200000),
('img001', 89000, 0, 5200, 11000, 310000),
('img002', 42000, 0, 2600, 6300, 190000),
('alb001', 128000, 0, 8600, 32000, 530000),
('alb002', 76000, 0, 3900, 17000, 270000);

insert into comments (id, content_id, user_id, parent_id, body, like_count, source) values
('cmt_v001_001', 'v001', 'seed_u002', null, 'The color palette feels very spring. It would look great on a dining table.', 128, 'seed'),
('cmt_v001_002', 'v001', 'seed_u004', null, 'How long should the floral foam soak before arranging?', 46, 'seed'),
('cmt_v001_003', 'v001', 'seed_u003', null, 'The third small-bottle setup is perfect for a rental desk.', 89, 'seed'),
('cmt_v001_004', 'v001', 'seed_u001', 'cmt_v001_002', 'I usually let it sink on its own so the water absorbs evenly.', 22, 'seed'),
('cmt_v002_001', 'v002', 'seed_u001', null, 'Saved this for my balcony. The layering works even in a small space.', 214, 'seed'),
('cmt_v002_002', 'v002', 'seed_u005', null, 'Would love to see a before-and-after layout, especially the walking path.', 37, 'seed'),
('cmt_v002_003', 'v002', 'seed_u003', null, 'The wood shelf and plants feel calm together. The light is just right.', 68, 'seed'),
('cmt_v002_004', 'v002', 'seed_u004', 'cmt_v002_001', 'For small sunny balconies, rosemary and sunflowers are reliable starters.', 41, 'seed'),
('cmt_v003_001', 'v003', 'seed_u004', null, 'Hydrating roses first really matters. Skipping it shortens the vase life.', 176, 'seed'),
('cmt_v003_002', 'v003', 'seed_u002', null, 'Should all rose leaves be removed? My vase water gets cloudy quickly.', 58, 'seed'),
('cmt_v003_003', 'v003', 'seed_u001', null, 'Now I get why angled cuts help: more surface area for water.', 73, 'seed');

insert into collections (id, user_id, name, is_default) values
('col_seed_u001_default', 'seed_u001', 'Default Favorites', true),
('col_seed_u002_default', 'seed_u002', 'Default Favorites', true),
('col_seed_u003_default', 'seed_u003', 'Default Favorites', true);

insert into collection_items (collection_id, content_id) values
('col_seed_u001_default', 'v001'),
('col_seed_u001_default', 'v002'),
('col_seed_u002_default', 'v003'),
('col_seed_u003_default', 'alb001');

insert into follows (follower_id, following_id) values
('seed_u001', 'u001'),
('seed_u001', 'u002'),
('seed_u002', 'u003'),
('seed_u003', 'u001'),
('seed_u004', 'u004'),
('seed_u005', 'u002');
