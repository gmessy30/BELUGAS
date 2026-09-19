select id, to_timestamp(observed_at_epoch_ms/1000) as observed, travel_bearing_degrees, travel_bearing_source
from public.sightings order by created_at desc limit 5;
