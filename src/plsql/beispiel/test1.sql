declare 
  x number;
  d date := to_date('03.09.1969');
begin
  dbms_output.put_line('Bla');
exception
when others then
  null;
end;
/